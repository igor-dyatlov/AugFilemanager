

package com.augustro.filemanager.asynchronous.services.ftp;

/**
 * Created by yashwanthreddyg on 09-06-2016.
 */

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.os.Environment;
import android.os.IBinder;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.widget.Toast;

import com.augustro.filemanager.R;
import com.augustro.filemanager.utils.files.CryptUtil;

import org.apache.ftpserver.ConnectionConfigFactory;
import org.apache.ftpserver.FtpServer;
import org.apache.ftpserver.FtpServerFactory;
import org.apache.ftpserver.ftplet.Authority;
import org.apache.ftpserver.ftplet.FtpException;
import org.apache.ftpserver.listener.ListenerFactory;
import org.apache.ftpserver.ssl.ClientAuth;
import org.apache.ftpserver.ssl.impl.DefaultSslConfiguration;
import org.apache.ftpserver.usermanager.impl.BaseUser;
import org.apache.ftpserver.usermanager.impl.WritePermission;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManagerFactory;

public class FtpService extends Service implements Runnable {

    public static final int DEFAULT_PORT = 2345;
    public static final String DEFAULT_USERNAME = "";
    public static final int DEFAULT_TIMEOUT = 600;   // default timeout, in sec
    public static final boolean DEFAULT_SECURE = false;
    public static final String PORT_PREFERENCE_KEY = "ftpPort";
    public static final String KEY_PREFERENCE_PATH = "ftp_path";
    public static final String KEY_PREFERENCE_USERNAME = "ftp_username";
    public static final String KEY_PREFERENCE_PASSWORD = "ftp_password_encrypted";
    public static final String KEY_PREFERENCE_TIMEOUT = "ftp_timeout";
    public static final String KEY_PREFERENCE_SECURE = "ftp_secure";
    public static final String DEFAULT_PATH = Environment.getExternalStorageDirectory().getAbsolutePath();
    public static final String INITIALS_HOST_FTP = "ftp://";
    public static final String INITIALS_HOST_SFTP = "ftps://";

    private static final String WIFI_AP_ADDRESS = "192.168.43.1";
    private static final char[] KEYSTORE_PASSWORD = "thisisthePassword".toCharArray();

    // Service will (global) broadcast when server start/stop
    static public final String ACTION_STARTED = "com.augustro.filemanager.services.ftpservice.FTPReceiver.FTPSERVER_STARTED";
    static public final String ACTION_STOPPED = "com.augustro.filemanager.services.ftpservice.FTPReceiver.FTPSERVER_STOPPED";
    static public final String ACTION_FAILEDTOSTART = "com.augustro.filemanager.services.ftpservice.FTPReceiver.FTPSERVER_FAILEDTOSTART";

    // RequestStartStopReceiver listens for these actions to start/stop this server
    static public final String ACTION_START_FTPSERVER = "com.augustro.filemanager.services.ftpservice.FTPReceiver.ACTION_START_FTPSERVER";
    static public final String ACTION_STOP_FTPSERVER = "com.augustro.filemanager.services.ftpservice.FTPReceiver.ACTION_STOP_FTPSERVER";

    static public final String TAG_STARTED_BY_TILE = "started_by_tile";  // attribute of action_started, used by notification
    public static final String INTENT_FOR_LAUNCHING_FTP_FRAGMENT = "launching_ftp_fragment";

    private String username, password;
    private boolean isPasswordProtected = false;

    private FtpServer server;
    protected static Thread serverThread = null;

    private boolean isStartedByTile = false;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            if(intent!=null) {
                isStartedByTile = intent.getBooleanExtra(TAG_STARTED_BY_TILE, false);
            }else
            {
                isStartedByTile = false;
            }
            int attempts = 10;
            while (serverThread != null) {
                if (attempts > 0) {
                    attempts--;
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ignored) {
                    }
                } else {
                    return START_STICKY;
                }
            }

            serverThread = new Thread(this);
            serverThread.start();
        } catch (Throwable e) {
            stopSelf();
        }

        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void run() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);

        FtpServerFactory serverFactory = new FtpServerFactory();
        ConnectionConfigFactory connectionConfigFactory = new ConnectionConfigFactory();
        connectionConfigFactory.setAnonymousLoginEnabled(true);

        serverFactory.setConnectionConfig(connectionConfigFactory.createConnectionConfig());

        String usernamePreference = preferences.getString(KEY_PREFERENCE_USERNAME, DEFAULT_USERNAME);
        if (!usernamePreference.equals(DEFAULT_USERNAME)) {
            username = usernamePreference;
            try {
                password = CryptUtil.decryptPassword(getApplicationContext(), preferences.getString(KEY_PREFERENCE_PASSWORD, ""));
                isPasswordProtected = true;
            } catch (GeneralSecurityException | IOException e) {
                e.printStackTrace();

                Toast.makeText(getApplicationContext(), getResources().getString(R.string.error), Toast.LENGTH_SHORT).show();
                // can't decrypt the password saved in preferences, remove the preference altogether
                // and start an anonymous connection instead
                preferences.edit().putString(FtpService.KEY_PREFERENCE_PASSWORD, "").apply();
                isPasswordProtected = false;
            }
        }

        BaseUser user = new BaseUser();
        if (!isPasswordProtected) {
            user.setName("anonymous");
        } else {
            user.setName(username);
            user.setPassword(password);
        }

        user.setHomeDirectory(preferences.getString(KEY_PREFERENCE_PATH, DEFAULT_PATH));
        List<Authority> list = new ArrayList<>();
        list.add(new WritePermission());
        user.setAuthorities(list);
        try {
            serverFactory.getUserManager().save(user);
        } catch (FtpException e) {
            e.printStackTrace();
        }
        ListenerFactory fac = new ListenerFactory();

        if (preferences.getBoolean(KEY_PREFERENCE_SECURE, DEFAULT_SECURE)) {

            try {
                KeyStore keyStore = KeyStore.getInstance("BKS", "BC");
                keyStore.load(getResources().openRawResource(R.raw.key), KEYSTORE_PASSWORD);

                KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                keyManagerFactory.init(keyStore, KEYSTORE_PASSWORD);

                TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                trustManagerFactory.init(keyStore);

                fac.setSslConfiguration(new DefaultSslConfiguration(keyManagerFactory,
                        trustManagerFactory, ClientAuth.WANT, "TLS",
                        null, "ftpserver"));
                fac.setImplicitSsl(true);
            } catch (GeneralSecurityException | IOException e) {
                preferences.edit().putBoolean(KEY_PREFERENCE_SECURE, false).apply();
            }
        }

        fac.setPort(getPort(preferences));
        fac.setIdleTimeout(preferences.getInt(KEY_PREFERENCE_TIMEOUT, DEFAULT_TIMEOUT));

        serverFactory.addListener("default", fac.createListener());
        try {
            server = serverFactory.createServer();
            server.start();
            sendBroadcast(new Intent(FtpService.ACTION_STARTED).putExtra(TAG_STARTED_BY_TILE, isStartedByTile));
        } catch (Exception e) {
            sendBroadcast(new Intent(FtpService.ACTION_FAILEDTOSTART));
        }
    }

    @Override
    public void onDestroy() {
        if (serverThread == null) {
            return;
        }
        serverThread.interrupt();
        try {
            serverThread.join(10000); // wait 10 sec for server thread to finish
        } catch (InterruptedException e) {
        }
        if (!serverThread.isAlive()) {
            serverThread = null;
        }
        if (server != null) {
            server.stop();
            sendBroadcast(new Intent(FtpService.ACTION_STOPPED));
        }
    }

    //Restart the service if the app is closed from the recent list
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);

        Intent restartService = new Intent(getApplicationContext(), this.getClass());
        restartService.setPackage(getPackageName());
        PendingIntent restartServicePI = PendingIntent.getService(
                getApplicationContext(), 1, restartService, PendingIntent.FLAG_ONE_SHOT);
        AlarmManager alarmService = (AlarmManager) getApplicationContext()
                .getSystemService(Context.ALARM_SERVICE);
        alarmService.set(AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + 2000, restartServicePI);
    }

    public static boolean isRunning() {
        return serverThread != null;
    }

    public static boolean isConnectedToLocalNetwork(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context
                .getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo ni = cm.getActiveNetworkInfo();
        boolean connected = ni != null && ni.isConnected()
                && (ni.getType() & (ConnectivityManager.TYPE_WIFI | ConnectivityManager.TYPE_ETHERNET)) != 0;
        if (!connected) {
            try {
                for (NetworkInterface netInterface : Collections.list(NetworkInterface
                        .getNetworkInterfaces())) {
                    if (netInterface.getDisplayName().startsWith("rndis")) {
                        connected = true;
                    }
                }
            } catch (SocketException e) {
                e.printStackTrace();
            }
        }
        return connected;
    }

    public static boolean isConnectedToWifi(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context
                .getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo ni = cm.getActiveNetworkInfo();
        return ni != null && ni.isConnected()
                && ni.getType() == ConnectivityManager.TYPE_WIFI;
    }

    public static boolean isEnabledWifiHotspot(Context context) {
        WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        Boolean enabled = callIsWifiApEnabled(wm);
        return enabled != null? enabled:false;
    }

    public static InetAddress getLocalInetAddress(Context context) {
        if (!isConnectedToLocalNetwork(context) && !isEnabledWifiHotspot(context)) {
            return null;
        }

        if (isConnectedToWifi(context)) {
            WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            int ipAddress = wm.getConnectionInfo().getIpAddress();
            if (ipAddress == 0)
                return null;
            return intToInet(ipAddress);
        }

        try {
            Enumeration<NetworkInterface> netinterfaces = NetworkInterface.getNetworkInterfaces();
            while (netinterfaces.hasMoreElements()) {
                NetworkInterface netinterface = netinterfaces.nextElement();
                Enumeration<InetAddress> addresses = netinterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();

                    if(WIFI_AP_ADDRESS.equals(address.getHostAddress()) && isEnabledWifiHotspot(context))
                        return address;

                    // this is the condition that sometimes gives problems
                    if (!address.isLoopbackAddress() && !address.isLinkLocalAddress() && !isEnabledWifiHotspot(context))
                        return address;
                }
            }
        } catch (SocketException e) {
            e.printStackTrace();
        }
        return null;
    }

    public static InetAddress intToInet(int value) {
        byte[] bytes = new byte[4];
        for (int i = 0; i < 4; i++) {
            bytes[i] = byteOfInt(value, i);
        }
        try {
            return InetAddress.getByAddress(bytes);
        } catch (UnknownHostException e) {
            // This only happens if the byte array has a bad length
            return null;
        }
    }

    public static byte byteOfInt(int value, int which) {
        int shift = which * 8;
        return (byte) (value >> shift);
    }

    public static int getPort(SharedPreferences preferences) {
        return preferences.getInt(PORT_PREFERENCE_KEY, DEFAULT_PORT);
    }

    @Nullable
    private static Boolean callIsWifiApEnabled(@NonNull WifiManager wifiManager) {
        Boolean r = null;
        try {
            Method method = wifiManager.getClass().getDeclaredMethod("isWifiApEnabled");
            r = (Boolean) method.invoke(wifiManager);
        } catch (NoSuchMethodException e) {
            e.printStackTrace();
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        } catch (InvocationTargetException e) {
            e.printStackTrace();
        }

        return r;
    }

}
