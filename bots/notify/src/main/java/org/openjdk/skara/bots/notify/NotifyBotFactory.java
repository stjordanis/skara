/*
 * Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package org.openjdk.skara.bots.notify;

import org.openjdk.skara.bot.*;
import org.openjdk.skara.email.EmailAddress;
import org.openjdk.skara.json.*;
import org.openjdk.skara.mailinglist.MailingListServerFactory;
import org.openjdk.skara.network.URIBuilder;
import org.openjdk.skara.storage.StorageBuilder;
import org.openjdk.skara.vcs.Tag;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class NotifyBotFactory implements BotFactory {
    private final Logger log = Logger.getLogger("org.openjdk.skara.bots");;

    @Override
    public String name() {
        return "notify";
    }

    @Override
    public List<Bot> create(BotConfiguration configuration) {
        var ret = new ArrayList<Bot>();
        var specific = configuration.specific();

        var database = specific.get("database").asObject();
        var databaseRepo = configuration.repository(database.get("repository").asString());
        var databaseRef = configuration.repositoryRef(database.get("repository").asString());
        var databaseName = database.get("name").asString();
        var databaseEmail = database.get("email").asString();

        var readyLabels = specific.get("ready").get("labels").stream()
                                  .map(JSONValue::asString)
                                  .collect(Collectors.toSet());
        var readyComments = specific.get("ready").get("comments").stream()
                                    .map(JSONValue::asObject)
                                    .collect(Collectors.toMap(obj -> obj.get("user").asString(),
                                                              obj -> Pattern.compile(obj.get("pattern").asString())));

        URI reviewIcon = null;
        if (specific.contains("reviews")) {
            if (specific.get("reviews").contains("icon")) {
                reviewIcon = URI.create(specific.get("reviews").get("icon").asString());
            }
        }
        URI commitIcon = null;
        if (specific.contains("commits")) {
            if (specific.get("commits").contains("icon")) {
                commitIcon = URI.create(specific.get("commits").get("icon").asString());
            }
        }
        for (var repo : specific.get("repositories").fields()) {
            var repoName = repo.name();
            var branchPattern = Pattern.compile("^master$");
            if (repo.value().contains("branches")) {
                branchPattern = Pattern.compile(repo.value().get("branches").asString());
            }

            var updaters = new ArrayList<RepositoryUpdateConsumer>();
            var prUpdaters = new ArrayList<PullRequestUpdateConsumer>();
            if (repo.value().contains("json")) {
                var folder = repo.value().get("folder").asString();
                var build = repo.value().get("build").asString();
                var version = repo.value().get("version").asString();
                updaters.add(new JsonUpdater(Path.of(folder), version, build));
            }
            if (repo.value().contains("mailinglists")) {
                var email = specific.get("email").asObject();
                var smtp = email.get("smtp").asString();
                var sender = EmailAddress.parse(email.get("sender").asString());
                var archive = URIBuilder.base(email.get("archive").asString()).build();
                var interval = email.contains("interval") ? Duration.parse(email.get("interval").asString()) : Duration.ofSeconds(1);
                var listServer = MailingListServerFactory.createMailmanServer(archive, smtp, interval);

                for (var mailinglist : repo.value().get("mailinglists").asArray()) {
                    var recipient = mailinglist.get("recipient").asString();
                    var recipientAddress = EmailAddress.parse(recipient);

                    var mode = MailingListUpdater.Mode.ALL;
                    if (mailinglist.contains("mode")) {
                        switch (mailinglist.get("mode").asString()) {
                            case "pr":
                                mode = MailingListUpdater.Mode.PR;
                                break;
                            case "pr-only":
                                mode = MailingListUpdater.Mode.PR_ONLY;
                                break;
                            default:
                                throw new RuntimeException("Unknown mode");
                        }
                    }

                    Map<String, String> headers = mailinglist.contains("headers") ?
                            mailinglist.get("headers").fields().stream()
                                       .collect(Collectors.toMap(JSONObject.Field::name, field -> field.value().asString())) :
                            Map.of();
                    var author = mailinglist.contains("author") ? EmailAddress.parse(mailinglist.get("author").asString()) : null;
                    var allowedDomains = author == null ? Pattern.compile(mailinglist.get("domains").asString()) : null;

                    var includeBranchNames = false;
                    if (mailinglist.contains("branchnames")) {
                        includeBranchNames = mailinglist.get("branchnames").asBoolean();
                    }
                    var reportNewTags = true;
                    if (mailinglist.contains("tags")) {
                        reportNewTags = mailinglist.get("tags").asBoolean();
                    }
                    var reportNewBranches = true;
                    if (mailinglist.contains("branches")) {
                        reportNewBranches = mailinglist.get("branches").asBoolean();
                    }
                    var reportNewBuilds = true;
                    if (mailinglist.contains("builds")) {
                        reportNewBuilds = mailinglist.get("builds").asBoolean();
                    }
                    updaters.add(new MailingListUpdater(listServer.getList(recipient), recipientAddress, sender, author,
                                                        includeBranchNames, reportNewTags, reportNewBranches, reportNewBuilds,
                                                        mode, headers, allowedDomains));
                }
            }
            if (repo.value().contains("issues")) {
                var issuesConf = repo.value().get("issues");
                var issueProject = configuration.issueProject(issuesConf.get("project").asString());
                var reviewLink = true;
                if (issuesConf.contains("reviewlink")) {
                    reviewLink = issuesConf.get("reviewlink").asBoolean();
                }
                var commitLink = true;
                if (issuesConf.contains("commitlink")) {
                    commitLink = issuesConf.get("commitlink").asBoolean();
                }
                var setFixVersion = false;
                String fixVersion = null;
                if (issuesConf.contains("fixversion")) {
                    setFixVersion = true;
                    fixVersion = issuesConf.get("fixversion").asString();
                }
                var updater = new IssueUpdater(issueProject, reviewLink, reviewIcon, commitLink, commitIcon, setFixVersion, fixVersion);
                updaters.add(updater);
                prUpdaters.add(updater);
            }

            if (updaters.isEmpty()) {
                log.warning("No consumers configured for notify bot repository: " + repoName);
                continue;
            }

            var baseName = repo.value().contains("basename") ? repo.value().get("basename").asString() : configuration.repositoryName(repoName);

            var tagStorageBuilder = new StorageBuilder<Tag>(baseName + ".tags.txt")
                    .remoteRepository(databaseRepo, databaseRef, databaseName, databaseEmail, "Added tag for " + repoName);
            var branchStorageBuilder = new StorageBuilder<ResolvedBranch>(baseName + ".branches.txt")
                    .remoteRepository(databaseRepo, databaseRef, databaseName, databaseEmail, "Added branch hash for " + repoName);
            var issueStorageBuilder = new StorageBuilder<PullRequestIssues>(baseName + ".prissues.txt")
                    .remoteRepository(databaseRepo, databaseRef, databaseName, databaseEmail, "Added pull request issue info for " + repoName);
            var bot = new NotifyBot(configuration.repository(repoName), configuration.storageFolder(), branchPattern,
                                    tagStorageBuilder, branchStorageBuilder, issueStorageBuilder, updaters, prUpdaters, readyLabels, readyComments);
            ret.add(bot);
        }

        return ret;
    }
}
