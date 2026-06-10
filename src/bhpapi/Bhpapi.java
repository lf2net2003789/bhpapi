package bhpapi;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.net.ssl.SSLContext;
import org.apache.http.HttpEntity;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.ssl.SSLContextBuilder;
import org.apache.http.util.EntityUtils;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

/**
 * BHP 與 MedicalRecord API 的命令列工具。
 *
 * 程式會從設定檔指定的路徑讀取 JSON，依命令列參數呼叫對應 API，
 * 再將 API 回傳的 JSON 寫回指定的輸出檔。
 */
public class Bhpapi {
    // 設定檔；若不存在會自動使用預設值建立。
    private static String configFile = "bhpa_config.ini";
    private static String Trusted_SSL_Hosts = "";

    // 原 BHP 驗證 API 使用的輸入與輸出 JSON 檔。
    private static String vaildJSONIN = "c:/temp/valid_jsonIn.txt";
    private static String vaildJSONOUT = "c:/temp/valid_jsonOut.txt";

    // MedicalRecord API 使用的輸入與輸出 JSON 檔。
    private static String medRecordJSONIN = "c:/temp/medRecord_jsonIn.txt";
    private static String medRecordJSONOUT = "c:/temp/medRecord_jsonOut.txt";

    // 原 BHP API 的基底網址。
    private static String apiUrl = "https://apcvpn.hpa.gov.tw/bhpApi/api";

    // MedicalRecord 測試與正式基底網址；由 MedicalRecord_Test_Mode 決定使用哪一個。
    private static String MedicalRecord_HPA_Test = "https://60.251.1.235/MedicalRecord_HPA/api/CHMSSForHis";
    private static String MedicalRecord_HPA = "https://203.65.42.199/MedicalRecord_HPA/api/CHMSSForHis";

    // MedicalRecord 帳號密碼，會由 bhpa_config.ini 載入。
    private static String MedicalRecord_Account = "0422351240";
    private static String MedicalRecord_Password = "md20zKLoLWQP1jLA";
    private static String MedicalRecord_Password_Update_Date = "";
    private static int MedicalRecord_Password_Remaining_Days = 0;

    private static boolean MedicalRecord_Test_Mode = true;
    private static final int MEDICAL_RECORD_PASSWORD_VALID_DAYS = 90;
    private static final DateTimeFormatter CONFIG_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    // API 所屬的基底網址類型。
    private enum ApiBase {
        BHP_API,
        MEDICAL_RECORD
    }

    // 將命令列參數對應到 API 路徑。
    private enum ApiMethod {
        VALID_ADULT("validadult", ApiBase.BHP_API, "/Adult/Valid"),
        VALID_ALL("validall", ApiBase.BHP_API, "/All/Valid"),
        VALID_BC_LIVER("validbcliver", ApiBase.BHP_API, "/BcLiver/Valid"),
        BC_REGISTER("bcregister", ApiBase.BHP_API, "/BcLiver/Register"),
        BC_CANCEL("bccancel", ApiBase.BHP_API, "/BcLiver/Cancel"),
        MED_RECORD_GETTOKENID("gettokenid", ApiBase.MEDICAL_RECORD, "/GetTokenID"),
        MED_RECORD_UPDATEPWD("updatepwd", ApiBase.MEDICAL_RECORD, "/UpdatePWD"),
        MED_RECORD_UPDATE_HEALTH_CARE("uploadhealthcare", ApiBase.MEDICAL_RECORD, "/UploadHealthCare");

        private final String argument;
        private final ApiBase base;
        private final String path;

        ApiMethod(String argument, ApiBase base, String path) {
            this.argument = argument;
            this.base = base;
            this.path = path;
        }

        private String getUrl() {
            return getBaseUrl(base) + path;
        }

        private boolean isMedicalRecord() {
            return base == ApiBase.MEDICAL_RECORD;
        }

        private boolean isGetToken() {
            return this == MED_RECORD_GETTOKENID;
        }

        private boolean isUpdatePassword() {
            return this == MED_RECORD_UPDATEPWD;
        }

        private static ApiMethod fromArgument(String argument) {
            for (ApiMethod method : values()) {
                if (method.argument.equalsIgnoreCase(argument)) {
                    return method;
                }
            }
            return null;
        }
    }

    public static void main(String[] args) {
        try {
            loadConfig();

            if(args.length != 1){
                System.out.println("參數數量不正確");
                return;
            }

            ApiMethod method = ApiMethod.fromArgument(args[0].toLowerCase());
            if(method == null){
                System.out.println("無此參數");
                return;
            }

            if(method.isMedicalRecord()){
                handleMedicalRecordMethod(method);
            }else{
                handleBhpaMethod(method);
            }
        } catch (IOException ex) {
            handleMainError("IOException", ex);
        } catch (ParseException ex) {
            handleMainError("ParseException", ex);
        } catch (ClassCastException ex) {
            handleMainError("InvalidInput", ex);
        }
    }

    // 處理原 BHP 驗證、登錄、取消類 API。
    private static void handleBhpaMethod(ApiMethod method) throws IOException, ParseException {
        JSONObject fileJson = readJsonFile(vaildJSONIN);
        JSONObject vaildObj = bhpaPost(method.getUrl(), fileJson);
        saveJSON(vaildObj);
    }

    // 將 MedicalRecord 指令分派到取得 token、更新密碼或資料上傳流程。
    private static void handleMedicalRecordMethod(ApiMethod method) throws IOException, ParseException {
        if(method.isGetToken()){
            JSONObject tokenObj = getMedicalRecordToken();
            System.out.println(tokenObj);
            return;
        }

        if(method.isUpdatePassword()){
            updateMedicalRecordPasswordFromApi();
            return;
        }

        uploadMedicalRecord(method);
    }

    // 使用設定檔中的帳號密碼取得 MedicalRecord token。
    private static JSONObject getMedicalRecordToken() {
        JSONObject loginJson = new JSONObject();
        loginJson.put("account", MedicalRecord_Account);
        loginJson.put("password", MedicalRecord_Password);
        return bhpaPost(ApiMethod.MED_RECORD_GETTOKENID.getUrl(), loginJson);
    }

    // 呼叫 MedicalRecord 更新密碼 API，並將回傳的新密碼寫回設定檔。
    private static void updateMedicalRecordPasswordFromApi() throws IOException {
        JSONObject changepwdJson = new JSONObject();
        changepwdJson.put("account", MedicalRecord_Account);
        changepwdJson.put("password", MedicalRecord_Password);

        JSONObject changepwdObj = bhpaPost(ApiMethod.MED_RECORD_UPDATEPWD.getUrl(), changepwdJson);
        System.out.println(changepwdObj);
        if(isSuccess(changepwdObj)){
            String newPassword = getJsonString(changepwdObj, "neW_PWD");
            if(!newPassword.equals("")){
                updateMedicalRecordPassword(newPassword);
            }
        }
    }

    // 取得 token 後寫入 MedicalRecord 輸入 JSON，再送出上傳 API。
    private static void uploadMedicalRecord(ApiMethod method) throws IOException, ParseException {
        JSONObject loginObj = getMedicalRecordToken();
        if(isSuccess(loginObj)){
            String tokenId = getJsonString(loginObj, "tokenid");
            JSONObject fileJson = readJsonFile(medRecordJSONIN);
            fileJson.put("tokenid", tokenId);
            JSONObject medRecordObj = bhpaPost(method.getUrl(), fileJson);
            saveJSON(medRecordObj, medRecordJSONOUT);
            System.out.println(medRecordObj);
        }
    }

    // 依 API 類型取得目前應使用的基底網址。
    private static String getBaseUrl(ApiBase base) {
        switch(base){
            case MEDICAL_RECORD:
                return MedicalRecord_Test_Mode ? MedicalRecord_HPA_Test : MedicalRecord_HPA;
            case BHP_API:
            default:
                return apiUrl;
        }
    }

    // 載入 bhpa_config.ini；若不存在則建立，且只有缺少 key 或格式需整理時才重寫。
    private static void loadConfig() throws IOException {
        File file = new File(configFile);
        if(!file.exists()){
            createDefaultConfig(file);
        }

        Properties props = new Properties();
        try (InputStreamReader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            props.load(reader);
        }

        Trusted_SSL_Hosts = props.getProperty("Trusted_SSL_Hosts", getDefaultTrustedSslHosts());
        vaildJSONIN = props.getProperty("validJSONIN", vaildJSONIN);
        vaildJSONOUT = props.getProperty("validJSONOUT", vaildJSONOUT);
        medRecordJSONIN = props.getProperty("medRecordJSONIN", medRecordJSONIN);
        medRecordJSONOUT = props.getProperty("medRecordJSONOUT", medRecordJSONOUT);
        apiUrl = props.getProperty("apiUrl", apiUrl);
        MedicalRecord_HPA_Test = props.getProperty("MedicalRecord_HPA_Test", MedicalRecord_HPA_Test);
        MedicalRecord_HPA = props.getProperty("MedicalRecord_HPA", MedicalRecord_HPA);
        MedicalRecord_Test_Mode = Boolean.parseBoolean(props.getProperty("MedicalRecord_Test_Mode", String.valueOf(MedicalRecord_Test_Mode)));
        MedicalRecord_Account = props.getProperty("MedicalRecord_Account", MedicalRecord_Account);
        MedicalRecord_Password = props.getProperty("MedicalRecord_Password", MedicalRecord_Password);
        MedicalRecord_Password_Update_Date = props.getProperty("MedicalRecord_Password_Update_Date", MedicalRecord_Password_Update_Date);
        updatePasswordRemainingDays();

        setCurrentConfigProperties(props);
        if(!isConfigCurrent(file, props)){
            saveConfig(file, props);
        }
    }

    // 使用目前預設值建立新的設定檔。
    private static void createDefaultConfig(File file) throws IOException {
        Properties props = new Properties();
        Trusted_SSL_Hosts = getDefaultTrustedSslHosts();
        setCurrentConfigProperties(props);
        saveConfig(file, props);
    }

    // 將目前記憶體中的設定值寫入 Properties。
    private static void setCurrentConfigProperties(Properties props) {
        props.setProperty("Trusted_SSL_Hosts", Trusted_SSL_Hosts);
        props.setProperty("validJSONIN", vaildJSONIN);
        props.setProperty("validJSONOUT", vaildJSONOUT);
        props.setProperty("medRecordJSONIN", medRecordJSONIN);
        props.setProperty("medRecordJSONOUT", medRecordJSONOUT);
        props.setProperty("apiUrl", apiUrl);
        props.setProperty("MedicalRecord_HPA_Test", MedicalRecord_HPA_Test);
        props.setProperty("MedicalRecord_HPA", MedicalRecord_HPA);
        props.setProperty("MedicalRecord_Test_Mode", String.valueOf(MedicalRecord_Test_Mode));
        props.setProperty("MedicalRecord_Account", MedicalRecord_Account);
        props.setProperty("MedicalRecord_Password", MedicalRecord_Password);
        props.setProperty("MedicalRecord_Password_Update_Date", MedicalRecord_Password_Update_Date);
        props.setProperty("MedicalRecord_Password_Remaining_Days", String.valueOf(MedicalRecord_Password_Remaining_Days));
    }

    // 依固定順序寫出設定檔，方便人工閱讀與維護。
    private static void saveConfig(File file, Properties props) throws IOException {
        try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
            writer.write(buildConfigContent(props));
        }
    }

    // 若設定檔內容已符合目前標準格式，就避免不必要的重寫。
    private static boolean isConfigCurrent(File file, Properties props) throws IOException {
        String current = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        return current.equals(buildConfigContent(props));
    }

    // 建立 bhpa_config.ini 的完整文字內容。
    private static String buildConfigContent(Properties props) {
        StringBuilder config = new StringBuilder();
        config.append("# bhpapi config\r\n");
        appendConfigLine(config, props, "Trusted_SSL_Hosts");
        config.append("\r\n");
        appendConfigLine(config, props, "validJSONIN");
        appendConfigLine(config, props, "validJSONOUT");
        config.append("\r\n");
        appendConfigLine(config, props, "medRecordJSONIN");
        appendConfigLine(config, props, "medRecordJSONOUT");
        config.append("\r\n");
        appendConfigLine(config, props, "apiUrl");
        appendConfigLine(config, props, "MedicalRecord_HPA_Test");
        appendConfigLine(config, props, "MedicalRecord_HPA");
        appendConfigLine(config, props, "MedicalRecord_Test_Mode");
        config.append("\r\n");
        appendConfigLine(config, props, "MedicalRecord_Account");
        appendConfigLine(config, props, "MedicalRecord_Password");
        appendConfigLine(config, props, "MedicalRecord_Password_Update_Date");
        appendConfigLine(config, props, "MedicalRecord_Password_Remaining_Days");
        return config.toString();
    }

    // 將單一 key/value 寫成設定檔的一行。
    private static void appendConfigLine(StringBuilder config, Properties props, String key) {
        config.append(key).append("=").append(props.getProperty(key, "")).append("\r\n");
    }

    // 依目前 API 網址產生預設 SSL 主機白名單。
    private static String getDefaultTrustedSslHosts() {
        return getHost(apiUrl) + "," + getHost(MedicalRecord_HPA_Test) + "," + getHost(MedicalRecord_HPA);
    }

    // 建立 HTTP client：允許不安全憑證，但主機名稱必須在白名單內。
    private static CloseableHttpClient createIgnoreSSLHttpClient() throws KeyManagementException, NoSuchAlgorithmException, KeyStoreException {
        try {
            SSLContext sslContext;
            sslContext = new SSLContextBuilder().loadTrustMaterial(null, (X509Certificate[] chain, String authType) -> true).build();
            SSLConnectionSocketFactory sslConnectionSocketFactory = new SSLConnectionSocketFactory(sslContext, (hostname, session) -> isTrustedHost(hostname));
            return HttpClients.custom().setSSLSocketFactory(sslConnectionSocketFactory).build();
        } catch (NoSuchAlgorithmException ex) {
            Logger.getLogger(Bhpapi.class.getName()).log(Level.SEVERE, null, ex);
            throw ex;
        } catch (KeyStoreException ex) {
            Logger.getLogger(Bhpapi.class.getName()).log(Level.SEVERE, null, ex);
            throw ex;
        }
    }

    // 僅允許 Trusted_SSL_Hosts 中列出的主機通過 SSL 主機檢查。
    private static boolean isTrustedHost(String hostname) {
        if(hostname == null){
            return false;
        }

        String[] trustedHosts = Trusted_SSL_Hosts.split(",");
        for(String trustedHost : trustedHosts){
            if(hostname.equalsIgnoreCase(trustedHost.trim())){
                return true;
            }
        }
        return false;
    }

    // 從 URL 字串取出 host，供 SSL 白名單預設值使用。
    private static String getHost(String url) {
        try {
            return new URL(url).getHost();
        } catch (IOException ex) {
            Logger.getLogger(Bhpapi.class.getName()).log(Level.WARNING, null, ex);
            return "";
        }
    }

    // 將 JSON POST 到 API；無論成功或錯誤都回傳 JSONObject。
    private static JSONObject bhpaPost(String requestUrl, JSONObject fileJson) {
        try (CloseableHttpClient httpClient = createIgnoreSSLHttpClient()) {
            HttpPost request = new HttpPost(requestUrl);
            StringEntity params = new StringEntity(fileJson.toJSONString(), "UTF-8");
            request.addHeader("Content-Type", "application/json");
            request.addHeader("Charset", "UTF-8");
            request.setEntity(params);
            RequestConfig requestConfig = RequestConfig.custom().setConnectTimeout(10 * 1000).setConnectionRequestTimeout(10 * 1000).setSocketTimeout(10 * 1000).setStaleConnectionCheckEnabled(true).build();
            request.setConfig(requestConfig);

            try (CloseableHttpResponse response = httpClient.execute(request)) {
                HttpEntity responseEntity = response.getEntity();
                int statusCode = response.getStatusLine().getStatusCode();
                String res = responseEntity == null ? "" : EntityUtils.toString(responseEntity, "UTF-8");
                if(statusCode == 200){
                    JSONParser parser = new JSONParser();
                    Object parsed = parser.parse(res);
                    if(parsed instanceof JSONObject){
                        return (JSONObject) parsed;
                    }
                    return errorJSON("InvalidResponse", "API 回應不是 JSON 物件", statusCode);
                }else{
                    JSONObject nullodj = errorJSON("HttpError", response.getStatusLine().toString(), statusCode);
                    nullodj.put("ResponseBody", res);
                    System.out.println(response);
                    return nullodj;
                }
            }
        } catch (KeyManagementException | NoSuchAlgorithmException | KeyStoreException ex) {
            Logger.getLogger(Bhpapi.class.getName()).log(Level.SEVERE, null, ex);
            return errorJSON("SSLClientError", ex.getMessage(), 0);
        } catch (IOException ex) {
            Logger.getLogger(Bhpapi.class.getName()).log(Level.SEVERE, null, ex);
            return errorJSON("IOException", ex.getMessage(), 0);
        } catch (ParseException ex) {
            Logger.getLogger(Bhpapi.class.getName()).log(Level.SEVERE, null, ex);
            return errorJSON("ParseException", ex.getMessage(), 0);
        }
    }

    // 建立統一格式的錯誤 JSON。
    private static JSONObject errorJSON(String error, String message, int statusCode) {
        JSONObject jobj = new JSONObject();
        jobj.put("Error", error);
        jobj.put("Message", message == null ? "" : message);
        jobj.put("StatusCode", statusCode);
        return jobj;
    }

    // MedicalRecord 成功回應會帶 msG_CODE = Y。
    private static boolean isSuccess(JSONObject jobj) {
        return "Y".equals(getJsonString(jobj, "msG_CODE"));
    }

    // 安全取得 JSON 欄位字串，避免 NullPointerException。
    private static String getJsonString(JSONObject jobj, String key) {
        if(jobj == null){
            return "";
        }
        Object value = jobj.get(key);
        return value == null ? "" : value.toString();
    }

    // 依密碼更新日期計算剩餘有效天數；密碼有效期限為 90 天。
    private static void updatePasswordRemainingDays() {
        LocalDate updateDate = parsePasswordUpdateDate();
        long usedDays = ChronoUnit.DAYS.between(updateDate, LocalDate.now());
        long remainingDays = MEDICAL_RECORD_PASSWORD_VALID_DAYS - usedDays;
        MedicalRecord_Password_Remaining_Days = (int)Math.max(0, remainingDays);
    }

    // 讀取設定檔中的密碼更新日期；若不存在或格式錯誤，改用今天。
    private static LocalDate parsePasswordUpdateDate() {
        if(MedicalRecord_Password_Update_Date == null || MedicalRecord_Password_Update_Date.trim().equals("")){
            return resetPasswordUpdateDateToToday();
        }

        try {
            return LocalDate.parse(MedicalRecord_Password_Update_Date, CONFIG_DATE_FORMAT);
        } catch (RuntimeException ex) {
            Logger.getLogger(Bhpapi.class.getName()).log(Level.WARNING, null, ex);
            return resetPasswordUpdateDateToToday();
        }
    }

    // 將密碼更新日期設為今天，並回傳今天日期。
    private static LocalDate resetPasswordUpdateDateToToday() {
        LocalDate today = LocalDate.now();
        MedicalRecord_Password_Update_Date = today.format(CONFIG_DATE_FORMAT);
        return today;
    }

    // 處理最外層錯誤：寫 log、輸出錯誤 JSON 檔，並印到 console。
    private static void handleMainError(String error, Exception ex) {
        Logger.getLogger(Bhpapi.class.getName()).log(Level.SEVERE, null, ex);
        JSONObject errorObj = errorJSON(error, ex.getMessage(), 0);
        try {
            saveJSON(errorObj);
        } catch (IOException saveEx) {
            Logger.getLogger(Bhpapi.class.getName()).log(Level.SEVERE, null, saveEx);
        }
        System.out.println(errorObj.toString());
    }

    // 讀取 UTF-8 JSON 檔，並解析成 JSONObject。
    private static JSONObject readJsonFile(String inputPath) throws IOException, ParseException {
        String line = "";
        StringBuilder str = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(inputPath), StandardCharsets.UTF_8))) {
            while((line = br.readLine()) != null){
                str.append(line);
            }
        }
        JSONParser jp = new JSONParser();
        return (JSONObject)jp.parse(str.toString());
    }

    // 將回應 JSON 寫到預設 BHP 輸出檔。
    private static void saveJSON(JSONObject jobj) throws IOException {
        saveJSON(jobj, vaildJSONOUT);
    }

    // 將回應 JSON 寫到指定路徑；必要時自動建立父資料夾。
    private static void saveJSON(JSONObject jobj, String outputPath) throws IOException {
        if(jobj == null){
            jobj = errorJSON("UnknownError", "No response object was created", 0);
        }
        File file = new File(outputPath);
        File parent = file.getParentFile();
        if(parent != null && !parent.exists()){
            parent.mkdirs();
        }
        if(!file.exists()){
            file.createNewFile();
        }
        try (BufferedWriter in = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
            in.write(jobj.toJSONString());
            in.flush();
        }
    }

    // 將 MedicalRecord API 核發的新密碼保存到 bhpa_config.ini。
    private static void updateMedicalRecordPassword(String newPassword) throws IOException {
        MedicalRecord_Password = newPassword;
        MedicalRecord_Password_Update_Date = LocalDate.now().format(CONFIG_DATE_FORMAT);
        updatePasswordRemainingDays();

        File file = new File(configFile);
        Properties props = new Properties();

        if(file.exists()){
            try (InputStreamReader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
                props.load(reader);
            }
        }

        setCurrentConfigProperties(props);
        props.setProperty("MedicalRecord_Password", MedicalRecord_Password);
        saveConfig(file, props);
    }
}
