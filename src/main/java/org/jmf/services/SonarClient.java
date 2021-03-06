package org.jmf.services;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.commons.collections4.MultiValuedMap;
import org.apache.commons.collections4.multimap.ArrayListValuedHashMap;
import org.apache.commons.lang3.StringUtils;
import org.jmf.util.FileUtils;
import org.jmf.util.HttpUtils;
import org.jmf.vo.Issue;
import org.jmf.vo.JsonIssue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Service for Sonar web service API.
 *
 * @author jose
 */
public class SonarClient {

  private static final String LOGIN = "api/authentication/login";

  private static final String API_DO_TRANSITION = "api/issues/do_transition";

  private static final String API_SEARCH = "api/issues/search";

  private static final String SET_COOKIE = "Set-Cookie";

  private static final Logger FILE_LOGGER = LoggerFactory.getLogger("file");

  private static final Logger CONSOLE_LOGGER = LoggerFactory.getLogger("console");

  private static final String OPEN = "OPEN";

  private static final String REOPENED = "REOPENED";

  private static final String CONFIRMED = "CONFIRMED";

  private static final String RESOLVED = "RESOLVED";

  private static final String FALSE_POSITIVE = "FALSE-POSITIVE";

  private static final String TRANSITION = "transition";

  private static final String JWT_SESSION = "JWT-SESSION";

  private static final String XSRF_TOKEN = "XSRF-TOKEN";

  private static final String X_XSRF_TOKEN = "X-XSRF-TOKEN";


  private final MultiValuedMap<String, String> headers; // Cookie and auth info

  private String baseUrl;

  /**
   * Constructor.
   */
  public SonarClient(String url) {

    this.headers = new ArrayListValuedHashMap<>();

    try {
      this.baseUrl = HttpUtils.getBaseUrl(url);
    } catch (java.lang.ArrayIndexOutOfBoundsException e) {
      FILE_LOGGER.error("Error parsing flagged issues URL.", e);
    }
  }


  public final String getBaseUrl() {
    return baseUrl;
  }


  /**
   * Connect to login url, get cookie, set cookie and HTTP credentials to headers.
   *
   * @param user HTTP User
   * @param passw HTTP Password
   * @param sonarUser Sonar useron
   * @param sonarPass Sonar pass
   * @return True if authenticated, False otherwise
   */
  public final Boolean authenticate(String user, String passw, String sonarUser, String sonarPass) {

    try {
      // Compose headers
      if (StringUtils.isNotEmpty(user) && StringUtils.isNotEmpty(passw)) {
        this.headers.put("Authorization",
            "Basic " + Base64.getEncoder().encodeToString((user + ":" + passw).getBytes("UTF8")));
      }

      // Compose POST params
      final Map<String, String> params = new ConcurrentHashMap<>();
      params.put("login", sonarUser);
      params.put("password", sonarPass);

      // Compose login URL
      final String loginUrl = this.baseUrl + "/" + LOGIN;

      // Launch request to Sonar server
      final MultiValuedMap<String, String> response = HttpUtils.httpRequest(loginUrl, this.headers, params);
      if (response != null && !response.get("body").contains("Authentication failed")) {
        setAuthHeaders(response.get(SET_COOKIE));
        return true;
      }

    } catch (KeyManagementException | NoSuchAlgorithmException | KeyStoreException | IOException e) {
      FILE_LOGGER.error("Error authenticating", e);
    }

    return false;
  }

  private void setAuthHeaders(Collection<String> cookies) {
    String token = cookies.stream().filter(cookie -> cookie.contains(XSRF_TOKEN)).findFirst().orElse("");
    String jwt = cookies.stream().filter(cookie -> cookie.contains(JWT_SESSION)).findFirst().orElse("");
    this.headers.put(X_XSRF_TOKEN, StringUtils.substringBetween(token, "=", ";"));
    this.headers.put("Cookie", jwt);

    return;
  }


  /**
   * Update project's issues based on flagged issues list.
   *
   * @param flaggedIssues List of flagged issues
   * @param project Project name
   */
  public final void copyIssuesToProject(List<Issue> flaggedIssues, String project) {

    final Map<String, List<Issue>> issuesByRuleMap = new HashMap<>();
    String rule;
    List<Issue> openIssues;

    int nIssues = flaggedIssues.size();
    int counter = 0;
    int nMatched = 0;

    for (final Issue flaggedIssue : flaggedIssues) {

      counter++;
      CONSOLE_LOGGER.info("Processing flagged issue {} {} of {}",
          new Object[]{flaggedIssue.getParsedComponent(), counter, nIssues});

      // search all open issues with the same rule as the flagged issue
      rule = flaggedIssue.getRule();
      if ((openIssues = issuesByRuleMap.get(rule)) == null) {
        // If there are no issues, launch search query
        String encodedRule;
        try {
          encodedRule = URLEncoder.encode(rule, "UTF-8");
        } catch (UnsupportedEncodingException e) {
          CONSOLE_LOGGER.error("Unsupported encoding UTF-8", e);
          return;
        }
        openIssues = searchIssues(
            this.baseUrl + "/" + API_SEARCH + "?rules=" + encodedRule + "&componentKeys=" + project);
        issuesByRuleMap.put(rule, openIssues);
      }

      // Find open issue with same features (component, rule, line) as
      // flagged issue
      final Issue matchOpenIssue = openIssues.stream().filter(openIssue -> openIssue.compare(flaggedIssue)).findAny()
          .orElse(null);

      if (matchOpenIssue != null) {
        nMatched++;
        updateIssue(matchOpenIssue, flaggedIssue);
      }
    }

    CONSOLE_LOGGER.info("{} flagged issues matched.", nMatched);
  }

  /**
   * Update status, resolution, action plans.
   *
   * Transitions: From OPEN      to ["confirm","resolve","falsepositive"] From CONFIRMED to
   * ["unconfirm","resolve","falsepositive"] From REOPENED  to ["confirm","resolve","falsepositive"]
   *
   * @return true (updated) or false (not updated)
   */
  public final boolean updateIssue(Issue openIssue, Issue flaggedIssue) {

    try {
      Map<String, String> params = new HashMap<>();

      String flaggedStatus = flaggedIssue.getStatus();
      String resolution = flaggedIssue.getResolution();

      // Compose parameters. First, set issue.
      if (openIssue.getKey() != null) {
        params.put("issue", openIssue.getKey());
      }

      // Then, set transition
      if (OPEN.equals(openIssue.getStatus()) || REOPENED.equals(openIssue.getStatus())) {
        if (RESOLVED.equals(flaggedStatus) && FALSE_POSITIVE.equals(resolution)) {
          params.put(TRANSITION, "falsepositive");
        } else if (RESOLVED.equals(flaggedStatus) && "WONTFIX".equals(resolution)) {
          params.put(TRANSITION, "wontfix");
        }
//                else if (CONFIRMED.equals(flaggedStatus)) {
//                    params.put(TRANSITION, "confirm");
//                }
      }

      if (params.size() == 2) {
        // Launch url
        CONSOLE_LOGGER.info("Updating {}:{}", openIssue.getParsedComponent(), openIssue.getLine());
        MultiValuedMap<String, String> response = HttpUtils.httpRequest
            (baseUrl + "/" + API_DO_TRANSITION, this.headers, params);
        if (response != null) {
          CONSOLE_LOGGER.info("Issue {}:{} updated", openIssue.getParsedComponent(), openIssue.getLine());
          return true;
        }
      }
    } catch (KeyManagementException | NoSuchAlgorithmException | KeyStoreException | IOException e) {
      FILE_LOGGER.error("Error updating issue.", e);
    }

    return false;
  }

  public final List<Issue> getIssuesFromProject(String projectKey) {
    return searchIssues(this.baseUrl + "/" + API_SEARCH + "?resolved=true&componentKeys=" + projectKey);
  }

  /**
   * Get list of issues from URL.
   *
   * @param url Sonar Web Api url
   * @return List of Issue object
   * @throws JsonParseException JSON Exception
   * @throws IOException IO Exception
   */
  public final List<Issue> searchIssues(String url) {
    // Initialize JSON mappers
    final ObjectMapper mapper = new ObjectMapper();
    mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    final List<Issue> issues = new ArrayList<>();

    Integer pageIndex = 0; // Current page
    JsonIssue obj; // JSON object representing server response

    try {
      do {
        // Launch HTTP GET Request (no parameters)
        Collection<String> requestResult = HttpUtils.httpRequest(url + "&pageIndex=" + (pageIndex + 1),
            this.headers, null).get("body");

        if (!requestResult.isEmpty()) {
          String body = requestResult.iterator().next();
          obj = mapper.readValue(body, JsonIssue.class);
          // Add list of issues extracted from current page
          issues.addAll(obj.getIssues());

          pageIndex = obj.getPaging().getPageIndex(); // Current page
        } else {
          return null;
        }
      } while (issues.size() < obj.getPaging().getTotal());
    } catch (KeyManagementException | NoSuchAlgorithmException | KeyStoreException | IOException e) {
      FILE_LOGGER.error("Error getting issues from URL.", e);
    }

    return issues;
  }

  /**
   * Get a list of issues from CSV file.
   *
   * @param path CSV file path
   * @return List of Issues
   */
  public final List<Issue> getIssuesFromFile(final String path) {

    final List<Issue> issues = new ArrayList<>();

    try {
      final List<String[]> rows = FileUtils.customCSVParser(path);

      // Get col names from first row
      final String[] cols = rows.get(0);

      if (cols == null || cols.length == 0) {
        return issues;
      }

      // Componse map: [Col name, Col position ]
      Map<String, Integer> positions = new ConcurrentHashMap<>();
      for (int i = 0; i < cols.length; i++) {
        positions.put(cols[i], i);
      }

      // ITerate over CSV rows and add issues to final list.
      // Skip first row.
      Issue issue;
      for (final String[] row : rows.subList(1, rows.size())) {
        issue = new Issue();
        issue.setKey(row[positions.get("key")]);
        issue.setComponent(row[positions.get("component")]);
        issue.setLine(row[positions.get("line")]);
        issue.setRule(row[positions.get("rule")]);
        issue.setSeverity(row[positions.get("severity")]);
        issue.setStatus(row[positions.get("status")]);
        issue.setResolution(row[positions.get("resolution")]);
        issues.add(issue);
      }
    } catch (IOException e) {
      FILE_LOGGER.error("Error getting issues from CSV file.", e);
    }
    return issues;
  }

}
