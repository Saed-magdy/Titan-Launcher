package com.dongfeng.shinecockpit.shinewave.radio;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.media.MediaMetadata;
import android.media.browse.MediaBrowser;
import android.media.session.MediaController;
import android.media.session.PlaybackState;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Connects to the vehicle factory DAB/DAB+ MediaBrowserService (com.ex.yhxdabw8.pad / com.ex.yhxcdabw8.pad)
 * to browse channels and dispatch playback commands without leaving the launcher.
 */
public final class DabBrowserController {
    private static final String[] DAB_PACKAGES = new String[]{"com.ex.yhxdabw8.pad", "com.ex.yhxcdabw8.pad"};
    private static final String FALLBACK_SERVICE = "com.ex.yhxdabw8.pad.service.DabMediaService";

    public interface Callback {
        void onDabUpdated(boolean connected, String title, boolean playing, String status, JSONArray itemsJson);
    }

    private final Context appContext;
    private final Callback callback;

    private MediaBrowser browser;
    private MediaController controller;
    private final List<MediaBrowser.MediaItem> currentItems = new ArrayList<>();
    private String currentTitle = "DAB / DAB+";
    private boolean currentPlaying = false;
    private String statusText = "جاهز للاتصال بـ DAB";

    public DabBrowserController(Context context, Callback callback) {
        this.appContext = context.getApplicationContext();
        this.callback = callback;
    }

    public void connectAndBrowse(final String parentId) {
        if (browser != null && browser.isConnected()) {
            browseNode(parentId != null && !parentId.isEmpty() ? parentId : browser.getRoot());
            return;
        }
        ComponentName component = resolveDabComponent();
        disconnect();
        statusText = "جاري الاتصال بخدمة DAB…";
        notifyCallback();

        try {
            browser = new MediaBrowser(appContext, component, new MediaBrowser.ConnectionCallback() {
                @Override
                public void onConnected() {
                    if (browser == null) return;
                    try {
                        controller = new MediaController(appContext, browser.getSessionToken());
                        controller.registerCallback(controllerCallback);
                        statusText = "DAB متصل";
                        String root = (parentId != null && !parentId.isEmpty()) ? parentId : browser.getRoot();
                        browseNode(root);
                    } catch (Throwable t) {
                        statusText = "تعذر ربط MediaController بـ DAB";
                        notifyCallback();
                    }
                }

                @Override
                public void onConnectionFailed() {
                    statusText = "خدمة DAB غير متوفرة على هذا النظام";
                    notifyCallback();
                }

                @Override
                public void onConnectionSuspended() {
                    statusText = "انقطع اتصال DAB مؤقتاً";
                    notifyCallback();
                }
            }, null);
            browser.connect();
        } catch (Throwable t) {
            statusText = "خدمة DAB غير متوفرة";
            notifyCallback();
        }
    }

    private void browseNode(String nodeId) {
        if (browser == null || !browser.isConnected() || nodeId == null || nodeId.isEmpty()) return;
        browser.subscribe(nodeId, new MediaBrowser.SubscriptionCallback() {
            @Override
            public void onChildrenLoaded(String pId, List<MediaBrowser.MediaItem> children) {
                currentItems.clear();
                if (children != null) currentItems.addAll(children);
                statusText = currentItems.isEmpty() ? "لا توجد محطات DAB" : (currentItems.size() + " محطة متاحة");
                notifyCallback();
            }

            @Override
            public void onError(String pId) {
                statusText = "تعذر تحميل قائمة DAB";
                notifyCallback();
            }
        });
    }

    public void playMediaId(String mediaId) {
        if (controller == null || mediaId == null || mediaId.isEmpty()) return;
        for (MediaBrowser.MediaItem item : currentItems) {
            if (mediaId.equals(item.getMediaId())) {
                if (item.isBrowsable()) {
                    browseNode(mediaId);
                    return;
                }
                break;
            }
        }
        try {
            controller.getTransportControls().playFromMediaId(mediaId, null);
        } catch (Throwable ignored) {}
    }

    public void togglePlayPause() {
        if (controller == null) return;
        if (currentPlaying) controller.getTransportControls().pause();
        else controller.getTransportControls().play();
    }

    public void next() {
        if (controller != null) controller.getTransportControls().skipToNext();
    }

    public void previous() {
        if (controller != null) controller.getTransportControls().skipToPrevious();
    }

    private final MediaController.Callback controllerCallback = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            currentPlaying = (state != null && state.getState() == PlaybackState.STATE_PLAYING);
            notifyCallback();
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            if (metadata != null) {
                String t = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
                if (t != null && !t.trim().isEmpty()) currentTitle = t.trim();
            }
            notifyCallback();
        }
    };

    private ComponentName resolveDabComponent() {
        for (String pkg : DAB_PACKAGES) {
            try {
                Intent i = new Intent("android.media.browse.MediaBrowserService").setPackage(pkg);
                ResolveInfo info = appContext.getPackageManager().resolveService(i, 0);
                if (info != null && info.serviceInfo != null) {
                    return new ComponentName(pkg, info.serviceInfo.name);
                }
            } catch (Throwable ignored) {}
        }
        return new ComponentName(DAB_PACKAGES[0], FALLBACK_SERVICE);
    }

    private void notifyCallback() {
        if (callback == null) return;
        JSONArray arr = new JSONArray();
        for (MediaBrowser.MediaItem item : currentItems) {
            try {
                JSONObject o = new JSONObject();
                o.put("mediaId", item.getMediaId() != null ? item.getMediaId() : "");
                CharSequence t = item.getDescription().getTitle();
                o.put("title", t != null ? t.toString() : "Station");
                o.put("browsable", item.isBrowsable());
                o.put("playable", item.isPlayable());
                arr.put(o);
            } catch (Throwable ignored) {}
        }
        callback.onDabUpdated(browser != null && browser.isConnected(), currentTitle, currentPlaying, statusText, arr);
    }

    public void disconnect() {
        if (controller != null) {
            try { controller.unregisterCallback(controllerCallback); } catch (Throwable ignored) {}
            controller = null;
        }
        if (browser != null) {
            try { browser.disconnect(); } catch (Throwable ignored) {}
            browser = null;
        }
    }
}
