package awais.instagrabber.asyncs;

import android.os.AsyncTask;
import android.os.Environment;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;

import awais.instagrabber.BuildConfig;
import awais.instagrabber.interfaces.FetchListener;
import awais.instagrabber.models.enums.MediaItemType;
import awais.instagrabber.models.PostModel;
import awais.instagrabber.utils.Constants;
import awais.instagrabber.utils.Utils;
import awaisomereport.LogCollector;

import static awais.instagrabber.utils.Constants.FOLDER_PATH;
import static awais.instagrabber.utils.Constants.FOLDER_SAVE_TO;
import static awais.instagrabber.utils.Utils.logCollector;

public final class PostsFetcher extends AsyncTask<Void, Void, PostModel[]> {
    private final String endCursor;
    private final String id;
    private final FetchListener<PostModel[]> fetchListener;
    private String username;

    public PostsFetcher(final String id, final FetchListener<PostModel[]> fetchListener) {
        this.id = id;
        this.endCursor = "";
        this.fetchListener = fetchListener;
    }

    public PostsFetcher(final String id, final String endCursor, final FetchListener<PostModel[]> fetchListener) {
        this.id = id;
        this.endCursor = endCursor == null ? "" : endCursor;
        this.fetchListener = fetchListener;
    }

    public PostsFetcher setUsername(final String username) {
        this.username = username;
        return this;
    }

    @Override
    protected PostModel[] doInBackground(final Void... voids) {
        final boolean isHashTag = id.charAt(0) == '#';

        final String url;
        if (isHashTag)
            url = "https://www.instagram.com/graphql/query/?query_hash=ded47faa9a1aaded10161a2ff32abb6b&variables=" +
                    "{\"tag_name\":\"" + id.substring(1) + "\",\"first\":150,\"after\":\"" + endCursor + "\"}";
        else
            url = "https://www.instagram.com/graphql/query/?query_id=17880160963012870&id=" + id + "&first=50&after=" + endCursor;

        PostModel[] result = null;
        try {
            final HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setUseCaches(false);
            conn.connect();

            if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                // to check if file exists
                final File downloadDir = new File(Environment.getExternalStorageDirectory(), "Download");
                File customDir = null;
                if (Utils.settingsHelper.getBoolean(FOLDER_SAVE_TO)) {
                    final String customPath = Utils.settingsHelper.getString(FOLDER_PATH);
                    if (!Utils.isEmpty(customPath)) customDir = new File(customPath);
                }

                final JSONObject mediaPosts = new JSONObject(Utils.readFromConnection(conn)).getJSONObject("data")
                        .getJSONObject(isHashTag ? "hashtag" : Constants.EXTRAS_USER)
                        .getJSONObject(isHashTag ? "edge_hashtag_to_media" : "edge_owner_to_timeline_media");

                final String endCursor;
                final boolean hasNextPage;

                final JSONObject pageInfo = mediaPosts.getJSONObject("page_info");
                if (pageInfo.has("has_next_page")) {
                    hasNextPage = pageInfo.getBoolean("has_next_page");
                    endCursor = hasNextPage ? pageInfo.getString("end_cursor") : null;
                } else {
                    hasNextPage = false;
                    endCursor = null;
                }

                final JSONArray edges = mediaPosts.getJSONArray("edges");
                final PostModel[] models = new PostModel[edges.length()];
                for (int i = 0; i < models.length; ++i) {
                    final JSONObject mediaNode = edges.getJSONObject(i).getJSONObject("node");
                    final JSONArray captions = mediaNode.getJSONObject("edge_media_to_caption").getJSONArray("edges");

                    final boolean isSlider = mediaNode.has("__typename") && mediaNode.getString("__typename").equals("GraphSidecar");
                    final boolean isVideo = mediaNode.getBoolean("is_video");

                    final MediaItemType itemType;
                    if (isSlider) itemType = MediaItemType.MEDIA_TYPE_SLIDER;
                    else if (isVideo) itemType = MediaItemType.MEDIA_TYPE_VIDEO;
                    else itemType = MediaItemType.MEDIA_TYPE_IMAGE;

                    models[i] = new PostModel(itemType, mediaNode.getString(Constants.EXTRAS_ID),
                            mediaNode.getString("display_url"), mediaNode.getString("thumbnail_src"),
                            mediaNode.getString(Constants.EXTRAS_SHORTCODE),
                            captions.length() > 0 ? captions.getJSONObject(0).getJSONObject("node").getString("text") : null,
                            mediaNode.getLong("taken_at_timestamp"));

                    Utils.checkExistence(downloadDir, customDir, username, isSlider, -1, models[i]);
                }

                if (models[models.length - 1] != null)
                    models[models.length - 1].setPageCursor(hasNextPage, endCursor);

                result = models;
            }

            conn.disconnect();
        } catch (Exception e) {
            if (logCollector != null)
                logCollector.appendException(e, LogCollector.LogFile.ASYNC_MAIN_POSTS_FETCHER, "doInBackground");
            if (BuildConfig.DEBUG) Log.e("AWAISKING_APP", "", e);
        }

        return result;
    }

    @Override
    protected void onPostExecute(final PostModel[] postModels) {
        if (fetchListener != null) fetchListener.onResult(postModels);
    }
}