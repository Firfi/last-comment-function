package ru.megaplan.jira.plugins.jql.function;

import com.atlassian.crowd.embedded.api.User;
import com.atlassian.jira.JiraDataType;
import com.atlassian.jira.JiraDataTypes;
import com.atlassian.jira.bc.issue.IssueService;
import com.atlassian.jira.bc.issue.search.SearchService;
import com.atlassian.jira.issue.Issue;
import com.atlassian.jira.issue.IssueManager;
import com.atlassian.jira.issue.comments.Comment;
import com.atlassian.jira.issue.comments.CommentManager;
import com.atlassian.jira.issue.search.SearchException;
import com.atlassian.jira.issue.search.SearchResults;
import com.atlassian.jira.jql.builder.JqlQueryBuilder;
import com.atlassian.jira.jql.operand.QueryLiteral;
import com.atlassian.jira.jql.query.QueryCreationContext;
import com.atlassian.jira.plugin.jql.function.AbstractJqlFunction;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.project.ProjectManager;
import com.atlassian.jira.security.PermissionManager;
import com.atlassian.jira.security.Permissions;
import com.atlassian.jira.user.util.UserManager;
import com.atlassian.jira.util.MessageSet;
import com.atlassian.jira.util.MessageSetImpl;
import com.atlassian.jira.util.NotNull;
import com.atlassian.jira.web.bean.PagerFilter;
import com.atlassian.query.Query;
import com.atlassian.query.clause.TerminalClause;
import com.atlassian.query.operand.FunctionOperand;
import com.atlassian.query.operand.Operand;
import com.google.common.base.Strings;
import org.apache.log4j.Logger;
import org.ofbiz.core.entity.GenericEntityException;

import java.util.*;

/**
 * Created with IntelliJ IDEA.
 * User: Firfi
 * Date: 6/7/12
 * Time: 12:41 AM
 * To change this template use File | Settings | File Templates.
 */
public class LastCommentator extends AbstractJqlFunction {



    public enum Type {
        AUTHOR,
        YOURMOMMY;
        public String toString() {
            return this.name().toLowerCase();
        }
    }

    private static final int MAGIC_TEN = -10;

    private static final Logger log = Logger.getLogger(LastCommentator.class);

    private final UserManager userManager;
    private final CommentManager commentManager;
    private final IssueManager issueManager;
    private final ProjectManager projectManager;
    private final PermissionManager permissionManager;
    private final SearchService searchService;



    public LastCommentator(UserManager userManager, CommentManager commentManager,
                           IssueManager issueManager, ProjectManager projectManager,
                           PermissionManager permissionManager,
                           SearchService searchService) {
        this.userManager = userManager;
        this.commentManager = commentManager;
        this.issueManager = issueManager;
        this.projectManager = projectManager;
        this.permissionManager = permissionManager;
        this.searchService = searchService;
    }

    @Override
    public MessageSet validate(User user, @NotNull FunctionOperand functionOperand,
                               @NotNull TerminalClause terminalClause) {

        MessageSet ms = new MessageSetImpl();
        List<String> typel = functionOperand.getArgs();
        Set<String> types = new HashSet<String>(typel);
        if (typel.size() != types.size()) ms.addErrorMessage("Duplicate args, please contain your eagerness");
        if (typel.size() == 0 || typel.size() == 1) {
            ms.addErrorMessage("I need some args. for example : [project key], " + Arrays.toString(Type.values()));
            return ms;
        }
        Project p = projectManager.getProjectObjByKey(typel.get(0));
        if (p == null) {
            ms.addErrorMessage("project not found");
            return ms;
        }
        if (!permissionManager.hasPermission(Permissions.BROWSE,p,user)) {
            ms.addErrorMessage("You do not have permissions to browse issues in this project");
            return ms;
        }
        try {
            Type.valueOf(typel.get(1).toUpperCase());
        } catch (IllegalArgumentException e) {
            ms.addErrorMessage("illegal commentator type. accepted types : " + Arrays.toString(Type.values()));
            return ms;
        } catch (NullPointerException n) {
            ms.addErrorMessage("second argument is null. how?");
            return ms;
        } catch (Exception e) {
            ms.addErrorMessage("some error happened");
            return ms;
        }

        return ms;
    }

    @Override
    public List<QueryLiteral> getValues(@NotNull QueryCreationContext queryCreationContext,
                                        @NotNull FunctionOperand operand,
                                        @NotNull TerminalClause terminalClause)
    {

        final List<QueryLiteral> literals = new LinkedList<QueryLiteral>();
        List<String> typel = new ArrayList<String>(operand.getArgs()); // we make list modifiable
        String projectKey = typel.remove(0);
        Project p = projectManager.getProjectObjByKey(projectKey);
        if (p == null) {
            log.error("Passed project key argument is null. How?");
            return literals;
        }
        for (String type : typel) {
            Collection<Issue> cissue = getValues(p, type, queryCreationContext.getUser());
            for (Issue issue : cissue) {
                literals.add(new QueryLiteral(operand, issue.getId()));
            }
        }
        return literals;

    }

    private Collection<Issue> getValues(Project project, String stype, User invoker) {
        Type type = Type.valueOf(stype.toUpperCase());
        Collection<Issue> result = null;
        switch(type) {
            case AUTHOR:
                result = getIssuesWithLastCommentatorAuthor(project, invoker, MAGIC_TEN);
                break;
            case YOURMOMMY:
                result = getIssuesWithLastCommentatorAuthor(project, invoker, 0);
                break;
        }
        return result==null?new ArrayList<Issue>():result;  //To change body of created methods use File | Settings | File Templates.
    }

    private Collection<Issue> getIssuesWithLastCommentatorAuthor(Project project, User invoker, int timeThreshold) {
        Collection<Long> ids = null;

        Query query = null;
        if (timeThreshold == 0) {
            query = JqlQueryBuilder.newBuilder().where().project(project.getId()).buildQuery();
        } else {
            Calendar calendar = Calendar.getInstance();
            calendar.add(Calendar.DAY_OF_MONTH, MAGIC_TEN);
            query = JqlQueryBuilder.newBuilder().where().project(project.getId()).and().updated().gt(calendar.getTime()).buildQuery();
        }
        SearchResults results = null;
        try {
            results = searchService.search(invoker, query, PagerFilter.getUnlimitedFilter());
        } catch (SearchException e) {
            e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
        }
        Collection<Issue> result = new ArrayList<Issue>();
        if (results == null) return result;
        for (Issue issue : results.getIssues()) {
            List<Comment> comments = commentManager.getComments(issue);
            if (comments.size() == 0) continue;
            Comment last = comments.get(comments.size()-1);
            if (last.getAuthorUser().equals(issue.getReporter())) result.add(issue);
        }
        return result;
    }

    @Override
    public int getMinimumNumberOfExpectedArguments() {
        return 2;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public JiraDataType getDataType() {
        return JiraDataTypes.ISSUE;
    }

    @Override
    public String getFunctionName()
    {
        return "lastCommentator";
    }

    @Override
    public boolean isList()
    {
        return true;
    }

}
