[![Gradle Status](https://gradleupdate.appspot.com/Cognifide/gradle-aem-plugin/status.svg?random=123)](https://gradleupdate.appspot.com/Cognifide/gradle-aem-plugin/status)
[![Apache License, Version 2.0, January 2004](docs/apache-license-badge.svg)](http://www.apache.org/licenses/)

# Introduction

Command-line tool to copy issue features (status, resolution) from one SonarQube project to another. 

Both projects (origin and destination) can be in the same of **different** SonarQube server.

Issue features currently covered:

- Statuses: Open
- Resolution: False-positive

**Scenario Example**:

SonarQube instance with Project_A containing thousands of issues. 

Some of them have been manually flagged to status "Confirmed" or "False-Positive".

At one point, Project_A is forked and a new analysis is run. Analysis results are stored in a new branch (let's call it "Production") in the same (or different) SonarQube server.
 
So, now we have:

- Project_A : Mix of "Open", "Confirmed" and "False-Positive" issues.
- Project_A Production : Same list of issues as in Project_A, but all are "Open".
  
As a developer, I want to flag the issues in 'Project_A Production' branch before starting to change code, so that new analysis contains same "flags" (Confirmed, False-positive) as the original one.

That is what this tool is for.

# Requirements

JRE 8

# Installation

1. Download sonar-issue-migrator-all.jar
2. Run command:
```sh
$ java -jar sonar-issue-migrator-all.jar --host=https://sonarqube.com \
                                         --user=user \
                                         --password=pa$$word \
                                         --source-project=proj:master \
                                         --target-project=proj:develop
```
3. Program will start running. Once execution is done, it will show:

- Total number of flagged issues obtained from URL (*flagged_issues_url* parameter in config file)
- Number of matched issues: how many open issues have been found in destination project ('Project_B') matching the flagged ones. The tool compares issues based on <Component, Rule, Line> triad. 



