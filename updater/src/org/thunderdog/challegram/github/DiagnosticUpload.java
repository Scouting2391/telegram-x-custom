package org.thunderdog.challegram.github;

import java.util.List;

/** Reviewed write protocol. Public/moved/ambiguous inboxes never receive a report. */
final class DiagnosticUpload {
  interface Api {
    Repository repository() throws Exception;
    List<Issue> issues(int page) throws Exception;
    Issue write(long number,String title,String body) throws Exception;
  }
  static final class Repository {
    final String name; final long id; final boolean privateRepo, hasIssues, archived;
    Repository(String name,long id,boolean privateRepo,boolean hasIssues,boolean archived) {
      this.name=name;this.id=id;this.privateRepo=privateRepo;this.hasIssues=hasIssues;this.archived=archived;
    }
  }
  static final class Issue {
    final String title; final long number; final boolean pullRequest;
    Issue(String title,long number,boolean pullRequest){this.title=title;this.number=number;this.pullRequest=pullRequest;}
  }
  static void verify(String expected,long id,Repository repo) {
    DiagnosticPolicy.verifyPrivate(expected,id,repo.name,repo.id,repo.privateRepo,repo.hasIssues,repo.archived);
  }
  static void send(String repo,long id,String title,String body,Api api) throws Exception {
    verify(repo,id,api.repository());
    long issue=0; boolean exhausted=true;
    for(int page=1;page<=10;page++) {
      List<Issue> items=api.issues(page);
      if(items.size()>100) throw new IllegalArgumentException("Invalid issue page.");
      for(Issue item:items) {
        if(title.equals(item.title) && !item.pullRequest) {
          if(issue!=0 || item.number<=0) throw new IllegalArgumentException("Ambiguous inbox report.");
          issue=item.number;
        }
      }
      if(items.size()<100){exhausted=false;break;}
    }
    if(exhausted) throw new IllegalArgumentException("Too many inbox issues.");
    verify(repo,id,api.repository());
    Issue result=api.write(issue,title,body);
    if(!title.equals(result.title) || result.pullRequest || result.number<=0 || (issue!=0 && issue!=result.number))
      throw new IllegalArgumentException("Unexpected inbox response.");
  }
}
