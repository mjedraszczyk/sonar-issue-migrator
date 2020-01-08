package org.jmf.client;

import java.util.List;
import java.util.concurrent.Callable;
import org.apache.commons.lang3.StringUtils;
import org.jmf.services.SonarClient;
import org.jmf.vo.Issue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * @author jose
 */
@Command()
public final class CommandLineClient implements Callable<Integer> {

  private static final Logger LOGGER = LoggerFactory.getLogger("Sonar Issue Migrator");

  @Option(names = {"-h", "--host"}, description = "Sonar host", required = true)
  private String host;
  @Option(names = {"-u", "--user"}, description = "Sonar user", required = true)
  private String user;
  @Option(names = {"-p", "--password"}, description = "Sonar password", required = true)
  private String password;
  @Option(names = {"-s", "--source-project"}, description = "Source project", required = true)
  private String sourceProject;
  @Option(names = {"-t", "--target-project"}, description = "Target project", required = true)
  private String targetProject;
  @Option(names = {"-H", "--target-host"}, description = "Sonar host")
  private String targetHost;
  @Option(names = {"-U", "--target-user"}, description = "Sonar user")
  private String targetUser;
  @Option(names = {"-P", "--target-password"}, description = "Sonar password")
  private String targetPassword;
  @Option(names = {"--basic-auth-user"}, description = "Sonar user")
  private String basicAuthUser;
  @Option(names = {"--basic-auth-password"}, description = "Sonar password")
  private String basicAuthPassword;
  @Option(names = {"--target-basic-auth-user"}, description = "Sonar user")
  private String targetBasicAuthUser;
  @Option(names = {"--target-basic-auth-password"}, description = "Sonar password")
  private String targetBasicAuthPassword;

  private CommandLineClient() {
  }

  public static void main(final String... args) {
    int exitCode = new CommandLine(new CommandLineClient()).execute(args);
    System.exit(exitCode);
  }

  @Override
  public Integer call() {
    targetHost = StringUtils.defaultString(targetHost, host);
    targetUser = StringUtils.defaultString(targetUser, user);
    targetPassword = StringUtils.defaultString(targetPassword, password);
    if (StringUtils.equals(host, targetHost)) {
      targetBasicAuthUser = StringUtils.defaultString(targetBasicAuthUser, basicAuthUser);
      targetBasicAuthPassword = StringUtils.defaultString(targetBasicAuthPassword, basicAuthPassword);
    }

    List<Issue> flaggedIssues;

    // Create Sonar Client for flagged issues server
    SonarClient sourceSonar = new SonarClient(host);
    if (sourceSonar.getBaseUrl() == null) {
      LOGGER.info("No URL found in configuration file for 'flagged' issues server (empty or malformed URL?). Exiting.");
      return 1;
    }

    // Try to authenticate and get list of issues from server
    LOGGER.info("Authenticating...\n");
    if (sourceSonar.authenticate(basicAuthUser, basicAuthPassword, user, password)) {
      LOGGER.info("Getting list of flagged issues...\n");
      flaggedIssues = sourceSonar.getIssuesFromProject(sourceProject);
    } else {
      LOGGER.info("Authentication failed. Please check credentials in configuration file.\n");
      return 1;
    }

    // If we have obtained a list of flagged issues...
    if (flaggedIssues != null && !flaggedIssues.isEmpty()) {
      LOGGER.info("Flagged issues list size: {}\n ", flaggedIssues.size());
      // Create Sonar Client for open issues
      SonarClient targetSonar = new SonarClient(targetHost);

      if (targetSonar.getBaseUrl() == null) {
        LOGGER.info("No URL found in configuration file for open issues server (empty or malformed URL?). Exiting.");
        return 1;
      }

      if (targetSonar.authenticate(targetBasicAuthUser, targetBasicAuthPassword, targetUser, targetPassword)) {
        // Copy issues to project
        targetSonar.copyIssuesToProject(flaggedIssues, targetProject);
      }
    }
    return 0;
  }
}
