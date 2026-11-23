package com.dongfeng.shinecockpit;

import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Titan Launcher GitHub In-App Auto Updater
 * Checks latest releases from GitHub API without blocking the UI or consuming extra resources.
 */
public class GitHubUpdateChecker {
    private static final String TAG = "TitanUpdater";
    private static final String REPO_API = "https://api.github.com/repos/Saed-magdy/Titan-Launcher/releases/latest";

    public static void checkForUpdates(final Context context, final boolean silentIfUpToDate) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // The installed version is read from the app itself (it comes from
                    // android:versionName in AndroidManifest.xml). There used to be a typed-in
                    // copy of the number here as a backup; every release someone had to remember
                    // to change it, and when they forgot, the updater compared against the wrong
                    // number. Now the manifest is the ONLY place the version is written.
                    String currentVersion = null;
                    try {
                        PackageInfo pInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
                        currentVersion = pInfo.versionName;
                    } catch (PackageManager.NameNotFoundException e) {
                        Log.e(TAG, "Could not read the installed version: " + e.getMessage());
                    }
                    if (currentVersion == null || currentVersion.trim().isEmpty()) {
                        // Without knowing our own version we cannot compare safely, so we do not
                        // offer an update at all (better than offering the same version forever).
                        if (!silentIfUpToDate) {
                            showToast(context, "تعذر معرفة إصدار اللانشر الحالي.");
                        }
                        return;
                    }

                    URL url = new URL(REPO_API);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestProperty("User-Agent", "TitanLauncher-App");
                    conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
                    conn.setConnectTimeout(8000);
                    conn.setReadTimeout(8000);

                    int responseCode = conn.getResponseCode();
                    if (responseCode == 200) {
                        BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) {
                            sb.append(line);
                        }
                        reader.close();

                        JSONObject release = new JSONObject(sb.toString());
                        final String latestTag = release.getString("tag_name").replace("v", "").replace("V", "").trim();
                        final String releaseNotes = release.optString("body", "تحسينات عامة وإصلاح أخطاء.");

                        String downloadUrl = null;
                        if (release.has("assets")) {
                            JSONArray assets = release.getJSONArray("assets");
                            for (int i = 0; i < assets.length(); i++) {
                                JSONObject asset = assets.getJSONObject(i);
                                String name = asset.getString("name");
                                if (name.toLowerCase().endsWith(".apk")) {
                                    downloadUrl = asset.getString("browser_download_url");
                                    break;
                                }
                            }
                        }

                        final String finalDownloadUrl = downloadUrl;
                        final String finalCurVer = currentVersion;

                        new Handler(Looper.getMainLooper()).post(new Runnable() {
                            @Override
                            public void run() {
                                if (finalDownloadUrl != null && isNewerVersion(finalCurVer, latestTag)) {
                                    showUpdateDialog(context, latestTag, releaseNotes, finalDownloadUrl);
                                } else {
                                    if (!silentIfUpToDate) {
                                        Toast.makeText(context, "اللانشر على أحدث إصدار بالفعل (v" + finalCurVer + ")", Toast.LENGTH_SHORT).show();
                                    }
                                }
                            }
                        });
                    } else {
                        if (!silentIfUpToDate) {
                            showToast(context, "تعذر الاتصال بـ GitHub للتحقق من التحديثات.");
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Update check failed: " + e.getMessage());
                    if (!silentIfUpToDate) {
                        showToast(context, "خطأ في فحص التحديثات، تأكد من الاتصال بالإنترنت.");
                    }
                }
            }
        }).start();
    }

    private static boolean isNewerVersion(String current, String latest) {
        try {
            String[] currParts = current.split("\\.");
            String[] lateParts = latest.split("\\.");
            int length = Math.max(currParts.length, lateParts.length);
            for (int i = 0; i < length; i++) {
                int c = i < currParts.length ? Integer.parseInt(currParts[i].replaceAll("[^0-9]", "")) : 0;
                int l = i < lateParts.length ? Integer.parseInt(lateParts[i].replaceAll("[^0-9]", "")) : 0;
                if (l > c) return true;
                if (l < c) return false;
            }
        } catch (Exception e) {
            Log.e(TAG, "Version parse error: " + e.getMessage());
        }
        return false;
    }

    private static void showUpdateDialog(final Context context, final String newVersion, final String notes, final String downloadUrl) {
        new AlertDialog.Builder(context)
                .setTitle("🚀 تحديث جديد متاح (v" + newVersion + ")")
                .setMessage("ملاحظات الإصدار الجديد:\n\n" + notes + "\n\nهل تريد تنزيل التحديث وتثبيته الآن؟")
                .setPositiveButton("تحديث الآن", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        downloadAndInstall(context, downloadUrl, newVersion);
                    }
                })
                .setNegativeButton("لاحقاً", null)
                .setCancelable(true)
                .show();
    }

    private static void downloadAndInstall(final Context context, final String url, final String version) {
        try {
            // Relax StrictMode VmPolicy immediately to prevent FileUriExposedException across processes
            try {
                android.os.StrictMode.setVmPolicy(new android.os.StrictMode.VmPolicy.Builder().build());
            } catch (Throwable ignored) {}

            Toast.makeText(context, "بدأ تنزيل التحديث في الخلفية...", Toast.LENGTH_LONG).show();

            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            request.setTitle("Titan Launcher v" + version);
            request.setDescription("جاري تنزيل التحديث الجديد...");
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);

            final String fileName = "Titan_Launcher_v" + version + ".apk";
            File destDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            final File destFile;
            if (destDir != null) {
                if (!destDir.exists()) {
                    destDir.mkdirs();
                }
                destFile = new File(destDir, fileName);
                if (destFile.exists()) {
                    destFile.delete();
                }
                request.setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName);
            } else {
                destDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (!destDir.exists()) {
                    destDir.mkdirs();
                }
                destFile = new File(destDir, fileName);
                if (destFile.exists()) {
                    destFile.delete();
                }
                request.setDestinationUri(Uri.fromFile(destFile));
            }

            final DownloadManager manager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
            if (manager == null) {
                Toast.makeText(context, "خدمة DownloadManager غير متاحة في النظام", Toast.LENGTH_SHORT).show();
                return;
            }

            final long downloadId = manager.enqueue(request);

            // Register broadcast receiver for download completion
            context.getApplicationContext().registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent intent) {
                    long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                    if (id == downloadId) {
                        try {
                            context.getApplicationContext().unregisterReceiver(this);
                        } catch (Exception ignored) {}

                        Toast.makeText(context, "اكتمل التنزيل! جاري فتح التثبيت...", Toast.LENGTH_SHORT).show();

                        try {
                            // Ensure StrictMode VmPolicy is disabled before starting install intent
                            android.os.StrictMode.setVmPolicy(new android.os.StrictMode.VmPolicy.Builder().build());

                            if (destFile != null && destFile.exists()) {
                                destFile.setReadable(true, false);
                                destFile.setWritable(true, false);
                            }

                            Intent installIntent = new Intent(Intent.ACTION_VIEW);
                            installIntent.setDataAndType(Uri.fromFile(destFile), "application/vnd.android.package-archive");
                            installIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            context.startActivity(installIntent);
                        } catch (Exception ex) {
                            Log.e(TAG, "Install via file:// failed, attempting content://: " + ex.getMessage(), ex);
                            try {
                                Uri contentUri = manager.getUriForDownloadedFile(downloadId);
                                if (contentUri != null) {
                                    Intent fallbackIntent = new Intent(Intent.ACTION_VIEW);
                                    fallbackIntent.setDataAndType(contentUri, "application/vnd.android.package-archive");
                                    fallbackIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                                    context.startActivity(fallbackIntent);
                                } else {
                                    throw new IllegalStateException("URI from DownloadManager is null");
                                }
                            } catch (Exception ex2) {
                                Log.e(TAG, "Fallback install also failed: " + ex2.getMessage(), ex2);
                                Toast.makeText(context, "تعذر فتح ملف التثبيت: " + ex.getMessage(), Toast.LENGTH_LONG).show();
                            }
                        }
                    }
                }
            }, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));

        } catch (Exception e) {
            Log.e(TAG, "Download error: " + e.getMessage(), e);
            Toast.makeText(context, "فشل بدء التنزيل: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private static void showToast(final Context context, final String msg) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show();
            }
        });
    }
}
