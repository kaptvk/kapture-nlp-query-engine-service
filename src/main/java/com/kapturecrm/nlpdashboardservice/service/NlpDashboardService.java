package com.kapturecrm.nlpdashboardservice.service;

import com.kapturecrm.nlpdashboardservice.dto.NlpDashboardReqDto;
import com.kapturecrm.nlpdashboardservice.dto.NlpDashboardResponse;
import com.kapturecrm.nlpdashboardservice.exception.KaptureException;
import com.kapturecrm.nlpdashboardservice.model.NlpDashboardPrompt;
import com.kapturecrm.nlpdashboardservice.repository.MysqlRepo;
import com.kapturecrm.nlpdashboardservice.repository.NlpDashboardRepository;
import com.kapturecrm.nlpdashboardservice.utility.BaseResponse;
import com.kapturecrm.nlpdashboardservice.utility.NlpDashboardUtils;
import com.kapturecrm.object.PartnerUser;
import com.kapturecrm.session.SessionManager;
import com.kapturecrm.utilobj.CommonUtils;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;

import static com.kapturecrm.nlpdashboardservice.utility.ConversionUtil.getTimestampForSql;

@Service
@RequiredArgsConstructor(onConstructor = @__(@Autowired))
@Slf4j
public class NlpDashboardService {

    @Value("${openai.apiKey}")
    private String apiKey;

    private final NlpDashboardRepository nlpDashboardRepository;
    private final NlpDashboardUtils nlpDashboardUtils;
    private final HttpServletRequest httpServletRequest;
    private final MysqlRepo mysqlRepo;

    public ResponseEntity<?> generateNlpDashboard(NlpDashboardReqDto reqDto) {
        try {
            NlpDashboardResponse resp = new NlpDashboardResponse();
            PartnerUser partnerUser = SessionManager.getPartnerUser(httpServletRequest);
            int cmId = partnerUser != null ? partnerUser.getCmId() : 415;
            int empId = partnerUser != null ? partnerUser.getEmpId() : 415;

            NlpDashboardPrompt nlpDashboardprompt = new NlpDashboardPrompt();
            setData(nlpDashboardprompt, cmId, empId, reqDto);
            Thread promptSaveThread = new Thread(() -> mysqlRepo.addPrompt(nlpDashboardprompt));
            promptSaveThread.start();

            OpenAiChatModel openAiModel = OpenAiChatModel.withApiKey(apiKey);
            String aiReply = openAiModel.generate(getPromptForAI(cmId, reqDto));
            String finalSql = validateAIGeneratedSQL(cmId, aiReply);
            log.info("FINAL-NLP-SQL: {}", finalSql);
            System.out.println(finalSql);
            List<LinkedHashMap<String, Object>> values = nlpDashboardRepository.findNlpDashboardDataFromSql(finalSql);

            switch (reqDto.getDashboardType().toLowerCase()) {
                case "text" -> {
                    String textResp = openAiModel.generate("prompt: " + reqDto.getPrompt() +
                            " data: " + JSONArray.fromObject(values).toString() +
                            " for above prompt give me a detail text response in less than 120 words by analyzing the data ");
                    resp.setTextResponse(textResp);
                }
            }

            if (!values.isEmpty()) {
                resp.setDashboardColumns(values.get(0).keySet());
            }
            resp.setDashboardValues(values);

            promptSaveThread.join();
            resp.setPromptId(nlpDashboardprompt.getId());

            return BaseResponse.success(resp);
        } catch (KaptureException ke) {
            log.warn("Error in generateNlpDashboard" + ke.getBaseResponse());
            return ke.getBaseResponse();
        } catch (Exception e) {
            log.error("Error in generateNlpDashboard", e);
            return BaseResponse.error(e);
        }
    }

    private void setData(NlpDashboardPrompt nlpDashboardprompt, int cmId, int empId, NlpDashboardReqDto reqDto) {
        nlpDashboardprompt.setCmId(cmId);
        nlpDashboardprompt.setPrompt(reqDto.getPrompt());
        nlpDashboardprompt.setCreateTime(CommonUtils.getCurrentTimestamp());
        nlpDashboardprompt.setEmpId(empId);
        nlpDashboardprompt.setDashboardType(reqDto.getDashboardType());
    }


    private String validateAIGeneratedSQL(int cmId, String aiReply) throws KaptureException {
        // todo validate reply if it has only sql its fine, else filter out sql alone check for ``` or ```sql
        String sql = aiReply.replaceAll("[\n;]", " ");
        if (!(sql.trim().startsWith("SELECT") || sql.trim().startsWith("select"))) {
            throw new KaptureException(BaseResponse.error(HttpStatus.UNPROCESSABLE_ENTITY, "Only select operation supported!"));
        }
        if (!(sql.contains("WHERE") || sql.contains("where"))) {
            sql += " where cm_id = " + cmId;
        } else if (sql.contains("WHERE") && !sql.split(" WHERE ")[1].contains("cm_id")
                || sql.contains("where") && sql.split(" where ")[1].contains("cm_id")) {
            sql = sql.replace("where", "where cm_id = " + cmId + " and ");
        }
        if (!(sql.contains("LIMIT") || sql.contains("limit"))) {
            sql += " LIMIT 10000";
        }
        return sql;
    }

    private String getPromptForAI(int cmId, NlpDashboardReqDto reqDto) {
        StringBuilder promptBuilder = new StringBuilder();

        JSONObject dbSchema = new JSONObject();
        NlpDashboardUtils.PromptInfo promptInfo = nlpDashboardUtils.convertTableNameAndFindDBSchema(reqDto.getPrompt(), dbSchema);
        promptBuilder.append("\nPROMPT: ").append(promptInfo.prompt());
        promptBuilder.append("\nDATABASE TABLES SCHEMA (tableName to columnName to columnDataType config): ").append(dbSchema);
        if (reqDto.getStartDate() != null && reqDto.getEndDate() != null) {
            promptBuilder.append("\nDATE RANGE: ").append(getTimestampForSql(reqDto.getStartDate()))
                    .append(" to ").append(getTimestampForSql(reqDto.getEndDate()));
        }
        // Initial prompt instructions
        promptBuilder.append("\nProvide a ClickHouse SQL query for PROMPT with correct syntax and proper table column names mentioned in DATABASE TABLES SCHEMA, to execute directly in ClickHouse.\n");

        // Conditionally add instructions based on dashboard type
        if (reqDto.getDashboardType().equalsIgnoreCase("table") || reqDto.getDashboardType().equalsIgnoreCase("text")) {
            promptBuilder.append("Select fewer than 15 essential columns.");
        } else {
            promptBuilder.append(
                    " select required columns for making " + reqDto.getDashboardType() +
                            ", adding alias names (" + NlpDashboardUtils.getAliasForChart(reqDto.getDashboardType()) + ") ie, like `column_name as alias`" +
                            " column used for alias `value` must be a numeric datatype and type will be a meaningful name of the column used for alias `value`" +
                            " also there can be multiple different type, hence value can be calculated based on type (like count of occurrence)"
            );
            promptBuilder.append("\nEnsure sql logic is like `SELECT {column1_name} AS name, countIf({column2 condition}) AS value, 'count of the {column2 condition}' AS type FROM table_name group by {column2_name}` ");
//            promptBuilder.append(" for creating a ").append(reqDto.getDashboardType()).append(" visualization.")
//                    .append("\nsample query logic: `SELECT $column1 AS 'name', COUNTIF(some condition) AS 'value', 'count of the condition' AS 'type' FROM table_name group by $column2`")
//                    .append("\nupdate column1, column2, condition  in sample query with appropriate column names from table");
        }

        // Common instructions
        promptBuilder.append("\nEnsure column names used are available in the DATABASE TABLES SCHEMA.");
        promptBuilder.append("\nExclude selecting columns like 'id', 'cm_id', and foreign key columns.");
        promptBuilder.append("\nInclude 'cm_id = ").append(cmId).append("' in the WHERE clause condition.");

        return promptBuilder.toString();
    }

}
