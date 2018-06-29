package com.meh.stuff.pr;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpEntity;
import org.apache.http.NameValuePair;
import org.apache.http.StatusLine;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicNameValuePair;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

public class CheckPullRequests {
    private static final Logger log = LoggerFactory.getLogger(CheckPullRequests.class);

    private static final String SCHEME_HTTPS = "https";
    private static final String SLACK_HOOK_HOST = "hooks.slack.com/";

    private static final String GHE_USERNAME = "nribeka";

    private static final String GHE_API_HOST = "api.github.com";
    private static final String GHE_PR_PATH = "/repos/nribeka/%s/pulls";
    private static final String GHE_REVIEWER_PATH = GHE_PR_PATH + "/%s/requested_reviewers";

    private static final String GHE_PR_WEB_URL = "https://github/nribeka/%s/pull/%s";

    private static final String MESSAGE_TEMPLATE = "PR Checker: %s need approval from %s.";
    private static final String[] REPOSITORY_NAMES = new String[]{
            "popular-movie"
    };

    private static Map<String, String> gheToSlack = new HashMap<String, String>();
    static {
        gheToSlack.put("nribeka", "<@nribeka>");
    }

    private static String GHE_PERSONAL_AUTH_TOKEN;
    private static String SLACK_BAMBOO_PATH;

    private static List<String> findPullRequests(String repository) throws Exception {
        List<String> pullRequests = new ArrayList<String>();

        CloseableHttpClient client = HttpClients.createDefault();
        try {
            URIBuilder uriBuilder = new URIBuilder();
            uriBuilder.setScheme(SCHEME_HTTPS);
            uriBuilder.setHost(GHE_API_HOST);
            uriBuilder.setPath(String.format(GHE_PR_PATH, repository));
            uriBuilder.setUserInfo(GHE_USERNAME, GHE_PERSONAL_AUTH_TOKEN);

            String authHeader = StringUtils.join(Arrays.asList(GHE_USERNAME, GHE_PERSONAL_AUTH_TOKEN), ":");
            String encodedAuthHeader = new String(Base64.encodeBase64(authHeader.getBytes()));

            HttpGet httpGet = new HttpGet(uriBuilder.build());
            httpGet.addHeader("Authorization", "Basic " + encodedAuthHeader);

            CloseableHttpResponse httpResponse = client.execute(httpGet);
            try {
                HttpEntity httpEntity = httpResponse.getEntity();

                String pullRequestsData = getResponseFromHttpUrl(httpEntity.getContent());
                JSONArray pullRequestArray = new JSONArray(pullRequestsData);
                for (int i = 0; i < pullRequestArray.length(); i++) {
                    JSONObject pullRequest = pullRequestArray.getJSONObject(i);
                    pullRequests.add(String.valueOf(pullRequest.getInt("number")));
                }
            } finally {
                httpResponse.close();
            }
        } finally {
            client.close();
        }

        return pullRequests;
    }

    private static List<String> findReviewers(String repository, String pullRequest) throws Exception {
        List<String> reviewers = new ArrayList<String>();

        CloseableHttpClient client = HttpClients.createDefault();
        try {
            URIBuilder uriBuilder = new URIBuilder();
            uriBuilder.setScheme(SCHEME_HTTPS);
            uriBuilder.setHost(GHE_API_HOST);
            uriBuilder.setPath(String.format(GHE_REVIEWER_PATH, repository, pullRequest));
            uriBuilder.setUserInfo(GHE_USERNAME, GHE_PERSONAL_AUTH_TOKEN);

            String authHeader = StringUtils.join(Arrays.asList(GHE_USERNAME, GHE_PERSONAL_AUTH_TOKEN), ":");
            String encodedAuthHeader = new String(Base64.encodeBase64(authHeader.getBytes()));

            HttpGet httpGet = new HttpGet(uriBuilder.build());
            httpGet.addHeader("Authorization", "Basic " + encodedAuthHeader);

            CloseableHttpResponse httpResponse = client.execute(httpGet);
            try {
                HttpEntity httpEntity = httpResponse.getEntity();

                String reviewersData = getResponseFromHttpUrl(httpEntity.getContent());
                JSONObject reviewersJsonData = new JSONObject(reviewersData);
                JSONArray reviewerUsers = reviewersJsonData.getJSONArray("users");
                for (int i = 0; i < reviewerUsers.length(); i++) {
                    JSONObject reviewer = reviewerUsers.getJSONObject(i);
                    reviewers.add(reviewer.getString("login"));
                }
            } finally {
                httpResponse.close();
            }
        } finally {
            client.close();
        }

        return reviewers;
    }

    private static String getResponseFromHttpUrl(InputStream in) {
        Scanner scanner = new Scanner(in);
        scanner.useDelimiter("\\A");

        boolean hasInput = scanner.hasNext();
        String response = null;
        if (hasInput) {
            response = scanner.next();
        }
        scanner.close();
        return response;
    }

    private static List<String> convertGheToSlackHandle(List<String> reviewers) {
        List<String> slackHandles = new ArrayList<String>();
        for (String reviewer : reviewers) {
            if (gheToSlack.containsKey(reviewer)) {
                slackHandles.add(gheToSlack.get(reviewer));
            }
        }
        return slackHandles;
    }

    private static void notifyReviewers(String prWebUrl, List<String> slackHandles) throws Exception {
        String reviewerAsString = StringUtils.join(slackHandles, ", ");
        String message = String.format(MESSAGE_TEMPLATE, prWebUrl, reviewerAsString);

        CloseableHttpClient client = HttpClients.createDefault();
        try {
            URIBuilder uriBuilder = new URIBuilder();
            uriBuilder.setScheme(SCHEME_HTTPS);
            uriBuilder.setHost(SLACK_HOOK_HOST);
            uriBuilder.setPath(SLACK_BAMBOO_PATH);

            HttpPost method = new HttpPost(uriBuilder.build());

            JSONObject attachments = new JSONObject();
            JSONObject object = new JSONObject();

            object.put("text", message);
            attachments.put("attachments", new JSONArray().put(object));
            attachments.put("channel", "#ngc-agile-fire-pr");
            List<NameValuePair> nameValuePairs = new ArrayList<NameValuePair>(1);
            nameValuePairs.add(new BasicNameValuePair("payload", attachments.toString()));
            method.setEntity(new UrlEncodedFormEntity(nameValuePairs, "UTF-8"));

            CloseableHttpResponse response = client.execute(method);
            try {
                HttpEntity httpEntity = response.getEntity();
                StatusLine statusLine = response.getStatusLine();
                log.info("Posting: " + message + ", status: " + statusLine.getStatusCode());
                log.info("Response body: " + getResponseFromHttpUrl(httpEntity.getContent()));
            } finally {
                response.close();
            }
        } finally {
            client.close();
        }
    }

    public static void main(String[] args) {
        SLACK_BAMBOO_PATH = args[0];
        GHE_PERSONAL_AUTH_TOKEN = args[1];
        try {
            for (String repositoryName : REPOSITORY_NAMES) {
                List<String> pullRequests = findPullRequests(repositoryName);
                for (String pullRequest : pullRequests) {
                    String prWebUrl = String.format(GHE_PR_WEB_URL, repositoryName, pullRequest);
                    List<String> reviewers = findReviewers(repositoryName, pullRequest);
                    if (!reviewers.isEmpty()) {
                        List<String> slackHandles = convertGheToSlackHandle(reviewers);
                        notifyReviewers(prWebUrl, slackHandles);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Unable to process all repository.", e);
        }
    }
}
