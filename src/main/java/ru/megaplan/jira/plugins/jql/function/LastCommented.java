package ru.megaplan.jira.plugins.jql.function;

import com.atlassian.crowd.embedded.api.User;
import com.atlassian.jira.JiraDataType;
import com.atlassian.jira.JiraDataTypes;
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
import com.atlassian.jira.jql.validator.NumberOfArgumentsValidator;
import com.atlassian.jira.plugin.jql.function.AbstractJqlFunction;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.project.ProjectManager;
import com.atlassian.jira.security.PermissionManager;
import com.atlassian.jira.security.Permissions;
import com.atlassian.jira.util.*;
import com.atlassian.jira.web.bean.PagerFilter;
import com.atlassian.query.Query;
import com.atlassian.query.clause.TerminalClause;
import com.atlassian.query.operand.FunctionOperand;
import org.apache.log4j.Logger;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Created with IntelliJ IDEA.
 * User: Firfi
 * Date: 6/7/12
 * Time: 2:34 AM
 * To change this template use File | Settings | File Templates.
 */
public class LastCommented extends AbstractJqlFunction {

    private final static Logger log = Logger.getLogger(LastCommented.class);

    private final ProjectManager projectManager;
    private final IssueManager issueManager;
    private final CommentManager commentManager;
    private final PermissionManager permissionManager;
    private final SearchService searchService;
    private static final int MIN_EXPECTED_ARGS = 2;
    private static final int MAX_EXPECTED_ARGS = 2;
    private static final Pattern DURATION_PATTERN = Pattern.compile("([-\\+]?)(\\d+)([mhdwMy]?)");


    public LastCommented(ProjectManager projectManager, IssueManager issueManager,
                  CommentManager commentManager, PermissionManager permissionManager,
                  SearchService searchService)
    {
        this.projectManager = projectManager;
        this.issueManager = issueManager;
        this.commentManager = commentManager;
        this.permissionManager = permissionManager;
        this.searchService = searchService;
    }

    public MessageSet validate(User searcher, FunctionOperand operand, final TerminalClause terminalClause)
    {
        I18nHelper i18n = getI18n();
        final MessageSet messageSet = new NumberOfArgumentsValidator(MIN_EXPECTED_ARGS, MAX_EXPECTED_ARGS, i18n).validate(operand);
        List<String> args = operand.getArgs();
        if (args.size() < MIN_EXPECTED_ARGS || args.size() > MAX_EXPECTED_ARGS) {
            messageSet.addErrorMessage("Invalid number of arguments. Min number : " + MIN_EXPECTED_ARGS + "; Max number : " + MAX_EXPECTED_ARGS);
            return messageSet;
        }
        if (operand.getArgs().size() == 2) {
            final String duration = operand.getArgs().get(1);
            if (!DURATION_PATTERN.matcher(duration).matches()) {
                messageSet.addErrorMessage(i18n.getText("jira.jql.date.function.duration.incorrect", operand.getName()));
            }
        }
        Project p = projectManager.getProjectObjByKey(args.get(0));
        if (p == null) {
            messageSet.addErrorMessage("project not found");
            return messageSet;
        }
        if (!permissionManager.hasPermission(Permissions.BROWSE,p,searcher)) {
            messageSet.addErrorMessage("You do not have permissions to browse issues in this project");
            return messageSet;
        }
        return messageSet;
    }

    @Override
    public List<QueryLiteral> getValues(@NotNull QueryCreationContext queryCreationContext,
                                        @NotNull FunctionOperand functionOperand,
                                        @NotNull TerminalClause terminalClause) {
        List<String> args = functionOperand.getArgs();
        Project p = projectManager.getProjectObjByKey(args.get(0));
        List<QueryLiteral> result = new ArrayList<QueryLiteral>();
        Collection<Issue> issues = findLastCommented(p, args.get(1), queryCreationContext.getUser());
        for (Issue issue : issues) {
            result.add(new QueryLiteral(functionOperand, issue.getId()));
        }
        return result;
    }

    private Collection<Issue> findLastCommented(Project p, String time, User searcher) {
        Calendar calendar = Calendar.getInstance();  // current time
        int durationAmount = -Math.abs(getDurationAmount(time));
        calendar.add(getDurationUnit(time), durationAmount);
        Query query=  JqlQueryBuilder.newBuilder().where().updated().gt(calendar.getTime()).and().project(p.getId()).buildQuery();
        SearchResults searchResults = null;
        try {
            searchResults = searchService.search(searcher, query, PagerFilter.getUnlimitedFilter());
        } catch (SearchException e) {
            e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
        }
        Collection<Issue> result = new LinkedList<Issue>();
        if (searchResults == null) {
            log.error("search result ist kaputt");
            return result;
        }
        for (Issue issue : searchResults.getIssues()) {
            List<Comment> comments = commentManager.getCommentsForUser(issue,issue.getReporter());
            if (comments.size() == 0) continue;
            Comment last = comments.get(comments.size()-1);
            Date lastCreated = last.getCreated();
            Date threshold = calendar.getTime();
            if (lastCreated.before(threshold)) {
                result.add(issue);
            }
        }
        return result;
    }

    @Override
    public int getMinimumNumberOfExpectedArguments() {
        return MIN_EXPECTED_ARGS;  //To change body of implemented methods use File | Settings | File Templates.
    }



    @Override
    public JiraDataType getDataType() {
        return JiraDataTypes.ISSUE;
    }

    @Override
    public String getFunctionName()
    {
        return "lastCommented";
    }

    @Override
    public boolean isList() {
        return true;
    }




    protected int getDurationUnit(String duration)
    {
        Matcher matcher = DURATION_PATTERN.matcher(duration);
        if (matcher.matches())
        {
            if (matcher.groupCount() > 2)
            {
                String unitGroup = matcher.group(3);
                if (unitGroup.equalsIgnoreCase("y"))
                {
                    return Calendar.YEAR;
                }
                else if (unitGroup.equals("M"))
                {
                    return Calendar.MONTH;
                }
                else if (unitGroup.equalsIgnoreCase("w"))
                {
                    return Calendar.WEEK_OF_MONTH;
                }
                else if (unitGroup.equalsIgnoreCase("d"))
                {
                    return Calendar.DAY_OF_MONTH;
                }
                else if (unitGroup.equalsIgnoreCase("h"))
                {
                    return Calendar.HOUR_OF_DAY;
                }
                else if (unitGroup.equals("m"))
                {
                    return Calendar.MINUTE;
                }
            }
        }
        return -1;
    }
    protected int getDurationAmount(String duration)
    {
        Matcher matcher = DURATION_PATTERN.matcher(duration);
        try
        {
            if (matcher.matches())
            {
                if (matcher.groupCount() > 1)
                {
                    if (matcher.group(1).equals("+"))
                    {
                        return Integer.parseInt(matcher.group(2));
                    }
                    if (matcher.group(1).equals("-"))
                    {
                        return -Integer.parseInt(matcher.group(2));
                    }
                }
            }
            return Integer.parseInt(matcher.group(2));
        }
        catch (NumberFormatException e)
        {
            // This should never happen as we have already formatted.
            // But can when JQL calls getValues even after a validation failure
            return 0;
        }

    }
}
