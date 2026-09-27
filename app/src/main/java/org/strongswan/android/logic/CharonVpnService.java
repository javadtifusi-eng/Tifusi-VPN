/*
 * Copyright (C) 2012-2025 Tobias Brunner
 * Copyright (C) 2012 Giuliano Grassi
 * Copyright (C) 2012 Ralf Sager
 * Copyright (C) 2026 Tifusi
 *
 * Copyright (C) secunet Security Networks AG
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the
 * Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.  See <http://www.fsf.org/copyleft/gpl.txt>.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * for more details.
 */

package org.strongswan.android.logic;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.system.OsConstants;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.Keep;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

import org.strongswan.android.utils.IPRange;
import org.strongswan.android.utils.IPRangeSet;
import org.strongswan.android.utils.SettingsWriter;
import org.strongswan.android.utils.Utils;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * IKEv2 through strongSwan's charon, built into the app (libandroidbridge and friends), instead of
 * the platform's IKEv2 client. The platform client is missing before Android 11 and, on some
 * phones (older Samsung builds), negotiates but never passes a packet; charon runs IKE and ESP in
 * this process, with ESP handled in userspace by libipsec on the TUN device this service creates.
 *
 * Reduced from strongSwan's own CharonVpnService: one profile at a time, handed over in the start
 * intent, no profile database, UI or BYOD. The class, its BuilderAdapter and the methods called
 * from native code keep their names and signatures, which libandroidbridge looks up via JNI.
 */
@Keep
public class CharonVpnService extends VpnService
{
	private static final String TAG = "TifusiCharon";
	private static final String ACTION_START = "com.tifusi.vpn.charon.START";
	private static final String ACTION_STOP = "com.tifusi.vpn.charon.STOP";
	private static final String CHANNEL_ID = "vpn_status";
	private static final int NOTIFICATION_ID = 1002;
	private static final String LOG_FILE = "charon.log";

	public static final String EXTRA_NAME = "name";
	public static final String EXTRA_SERVER = "server";
	public static final String EXTRA_PORT = "port";
	public static final String EXTRA_TYPE = "type";
	public static final String EXTRA_USERNAME = "username";
	public static final String EXTRA_PASSWORD = "password";
	public static final String EXTRA_REMOTE_ID = "remote_id";
	public static final String EXTRA_LOCAL_ID = "local_id";
	public static final String EXTRA_CA_PEM = "ca_pem";
	public static final String EXTRA_P12 = "p12";
	public static final String EXTRA_P12_PASSWORD = "p12_password";
	public static final String EXTRA_MTU = "mtu";
	public static final String EXTRA_DNS = "dns";
	public static final String EXTRA_RUN_ID = "run_id";

	/* status codes reported by libandroidbridge via updateStatus() */
	static final int STATE_CHILD_SA_UP = 1;
	static final int STATE_CHILD_SA_DOWN = 2;
	static final int STATE_AUTH_ERROR = 3;
	static final int STATE_PEER_AUTH_ERROR = 4;
	static final int STATE_LOOKUP_ERROR = 5;
	static final int STATE_UNREACHABLE_ERROR = 6;
	static final int STATE_CERTIFICATE_UNAVAILABLE = 7;
	static final int STATE_GENERIC_ERROR = 8;

	public enum State
	{
		DISABLED, CONNECTING, CONNECTED, FAILED
	}

	/** What the app's VpnController follows; [sRunId] ties it to the start that caused it. */
	private static volatile State sState = State.DISABLED;
	private static volatile String sError;
	private static volatile long sRunId;
	private static volatile boolean sLibraryLoaded;
	private static volatile String sLibraryError;

	static
	{
		try
		{
			System.loadLibrary("androidbridge");
			sLibraryLoaded = true;
		}
		catch (Throwable t)
		{
			sLibraryError = t.toString();
		}
	}

	/* one thread starts and stops charon, in order */
	private final ExecutorService mWorker = Executors.newSingleThreadExecutor();
	private final BuilderAdapter mBuilderAdapter = new BuilderAdapter();
	private volatile Intent mProfile;
	private volatile boolean mRunning;
	private volatile boolean mIsDisconnecting;
	/* a duplicate of the TUN descriptor handed to charon: closing it on stop makes sure the
	 * interface (and the VPN key icon) goes away even if charon's own copy lingers */
	private ParcelFileDescriptor mTunCopy;

	public static boolean isAvailable()
	{
		return sLibraryLoaded;
	}

	public static String unavailableReason()
	{
		return sLibraryError;
	}

	public static State getState()
	{
		return sState;
	}

	public static String getError()
	{
		return sError;
	}

	public static long getRunId()
	{
		return sRunId;
	}

	/** Starts (or switches to) the profile in [extras]; VPN consent must already be granted. */
	public static void start(Context context, Intent extras, long runId)
	{
		sRunId = runId;
		sError = null;
		sState = State.CONNECTING;
		Intent intent = new Intent(context, CharonVpnService.class)
			.setAction(ACTION_START)
			.putExtras(extras)
			.putExtra(EXTRA_RUN_ID, runId);
		ContextCompat.startForegroundService(context, intent);
	}

	public static void stop(Context context, long runId)
	{
		sRunId = runId;
		Intent intent = new Intent(context, CharonVpnService.class).setAction(ACTION_STOP);
		try
		{
			context.startService(intent);
		}
		catch (Exception e)
		{	/* not running, or the app is in the background with nothing to stop */
			sState = State.DISABLED;
		}
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId)
	{
		String action = intent != null ? intent.getAction() : null;
		if (ACTION_START.equals(action))
		{
			startInForeground(intent.getStringExtra(EXTRA_NAME));
			final Intent profile = intent;
			mWorker.execute(() -> startConnection(profile));
		}
		else
		{
			mWorker.execute(() -> {
				stopConnection();
				sState = State.DISABLED;
				stopForegroundCompat();
				stopSelf();
			});
		}
		return START_NOT_STICKY;
	}

	@Override
	public void onRevoke()
	{	/* the user switched the VPN off in Settings, or another VPN app took over */
		mWorker.execute(() -> {
			stopConnection();
			sError = "revoked";
			sState = State.FAILED;
			stopForegroundCompat();
			stopSelf();
		});
	}

	@Override
	public void onDestroy()
	{
		mWorker.execute(this::stopConnection);
		mWorker.shutdown();
		super.onDestroy();
	}

	private void startConnection(Intent profile)
	{
		stopConnection();
		if (!sLibraryLoaded)
		{
			fail("strongSwan libraries missing: " + sLibraryError);
			return;
		}
		mProfile = profile;
		mIsDisconnecting = false;
		SimpleFetcher.enable();
		mBuilderAdapter.setProfile(profile);
		String logFile = getFilesDir().getAbsolutePath() + File.separator + LOG_FILE;
		if (!initializeCharon(mBuilderAdapter, logFile, getFilesDir().getAbsolutePath(), false, false))
		{
			fail("charon failed to start");
			return;
		}
		mRunning = true;
		Log.i(TAG, "charon started");

		SettingsWriter writer = new SettingsWriter();
		writer.setValue("global.language", Locale.getDefault().getLanguage());
		writer.setValue("global.mtu", profile.getIntExtra(EXTRA_MTU, 1400));
		writer.setValue("global.nat_keepalive", 20);
		writer.setValue("global.rsa_pss", false);
		/* no CRL/OCSP fetches: nothing is excluded from the tunnel for them */
		writer.setValue("global.crl", false);
		writer.setValue("global.ocsp", false);
		writer.setValue("connection.type", profile.getStringExtra(EXTRA_TYPE));
		writer.setValue("connection.server", profile.getStringExtra(EXTRA_SERVER));
		writer.setValue("connection.port", profile.getIntExtra(EXTRA_PORT, 500));
		writer.setValue("connection.username", profile.getStringExtra(EXTRA_USERNAME));
		writer.setValue("connection.password", profile.getStringExtra(EXTRA_PASSWORD));
		writer.setValue("connection.local_id", profile.getStringExtra(EXTRA_LOCAL_ID));
		writer.setValue("connection.remote_id", profile.getStringExtra(EXTRA_REMOTE_ID));
		/* no certificate requests: with them the server sends only the intermediates on the way to
		 * a CA this phone listed, and when none matches (older CA stores) just its own certificate,
		 * which the phone then cannot verify (peer_auth_failed). Without them it sends its chain. */
		writer.setValue("connection.certreq", false);
		writer.setValue("connection.strict_revocation", false);
		writer.setValue("connection.ike_proposal", (String)null);
		writer.setValue("connection.esp_proposal", (String)null);
		initiate(writer.serialize());
	}

	private void stopConnection()
	{
		if (mRunning)
		{
			mIsDisconnecting = true;
			SimpleFetcher.disable();
			deinitializeCharon();
			Log.i(TAG, "charon stopped");
			mRunning = false;
		}
		mBuilderAdapter.closeTun();
		mProfile = null;
	}

	private void fail(String error)
	{
		Log.e(TAG, error);
		stopConnection();
		sError = error;
		sState = State.FAILED;
		stopForegroundCompat();
		stopSelf();
	}

	private void startInForeground(String name)
	{
		NotificationManager manager = getSystemService(NotificationManager.class);
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager != null)
		{
			manager.createNotificationChannel(new NotificationChannel(
				CHANNEL_ID, getString(com.tifusi.vpn.R.string.app_name), NotificationManager.IMPORTANCE_LOW));
		}
		Intent open = getPackageManager().getLaunchIntentForPackage(getPackageName());
		PendingIntent pending = open == null ? null :
			PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
		Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
			.setSmallIcon(com.tifusi.vpn.R.mipmap.ic_launcher)
			.setContentTitle(getString(com.tifusi.vpn.R.string.app_name))
			.setContentText(name != null ? name : "IKEv2")
			.setContentIntent(pending)
			.setOngoing(true)
			.setPriority(NotificationCompat.PRIORITY_LOW)
			.build();
		int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ?
			ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE : 0;
		ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type);
	}

	private void stopForegroundCompat()
	{
		ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
	}

	/**
	 * Updates the state of the current connection.
	 * Called via JNI by different threads (but not concurrently).
	 */
	public void updateStatus(int status)
	{
		switch (status)
		{
			case STATE_CHILD_SA_DOWN:
				if (!mIsDisconnecting)
				{
					sState = State.CONNECTING;
				}
				break;
			case STATE_CHILD_SA_UP:
				sState = State.CONNECTED;
				break;
			case STATE_AUTH_ERROR:
				failFromCharon("auth_failed");
				break;
			case STATE_PEER_AUTH_ERROR:
				failFromCharon("peer_auth_failed");
				break;
			case STATE_LOOKUP_ERROR:
				failFromCharon("lookup_failed");
				break;
			case STATE_UNREACHABLE_ERROR:
				failFromCharon("unreachable");
				break;
			case STATE_CERTIFICATE_UNAVAILABLE:
				failFromCharon("certificate_unavailable");
				break;
			case STATE_GENERIC_ERROR:
				failFromCharon("generic_error");
				break;
			default:
				Log.e(TAG, "Unknown status code received");
				break;
		}
	}

	/** Errors arrive on charon's own threads; charon is torn down from the worker. */
	private void failFromCharon(String error)
	{
		if (mIsDisconnecting)
		{
			return;
		}
		sError = error;
		mWorker.execute(() -> fail(error));
	}

	/** Called via JNI; BYOD is not built in. */
	public void updateImcState(int value)
	{
	}

	/** Called via JNI; BYOD is not built in. */
	public void addRemediationInstruction(String xml)
	{
	}

	/**
	 * DER encoded CA certificates charon may trust for the server: the profile's own CA when it
	 * has one, plus the system and user CA store (Let's Encrypt and other public CAs).
	 * Called via JNI.
	 */
	private byte[][] getTrustedCertificates()
	{
		ArrayList<byte[]> certs = new ArrayList<>();
		Intent profile = mProfile;
		String pem = profile != null ? profile.getStringExtra(EXTRA_CA_PEM) : null;
		if (pem != null)
		{
			try
			{
				CertificateFactory factory = CertificateFactory.getInstance("X.509");
				for (Certificate cert : factory.generateCertificates(new ByteArrayInputStream(pem.getBytes())))
				{
					certs.add(cert.getEncoded());
				}
			}
			catch (Exception e)
			{
				Log.w(TAG, "profile CA certificate unusable", e);
			}
		}
		try
		{
			KeyStore store = KeyStore.getInstance("AndroidCAStore");
			store.load(null, null);
			Enumeration<String> aliases = store.aliases();
			while (aliases.hasMoreElements())
			{
				Certificate cert = store.getCertificate(aliases.nextElement());
				if (cert instanceof X509Certificate)
				{
					certs.add(cert.getEncoded());
				}
			}
		}
		catch (Exception e)
		{
			Log.w(TAG, "system CA store unavailable", e);
		}
		return certs.toArray(new byte[0][]);
	}

	/** The profile's PKCS#12 bundle, for certificate authentication. */
	private KeyStore userKeyStore() throws Exception
	{
		Intent profile = mProfile;
		String p12 = profile != null ? profile.getStringExtra(EXTRA_P12) : null;
		if (p12 == null)
		{
			return null;
		}
		String password = profile.getStringExtra(EXTRA_P12_PASSWORD);
		KeyStore store = KeyStore.getInstance("PKCS12");
		store.load(new ByteArrayInputStream(Base64.decode(p12, Base64.DEFAULT)),
				   password != null ? password.toCharArray() : new char[0]);
		return store;
	}

	private String userKeyAlias(KeyStore store) throws Exception
	{
		for (String alias : Collections.list(store.aliases()))
		{
			if (store.isKeyEntry(alias))
			{
				return alias;
			}
		}
		return null;
	}

	/** Called via JNI: the user certificate chain, user certificate first. */
	private byte[][] getUserCertificate() throws Exception
	{
		KeyStore store = userKeyStore();
		String alias = store != null ? userKeyAlias(store) : null;
		if (alias == null)
		{
			return null;
		}
		Certificate[] chain = store.getCertificateChain(alias);
		if (chain == null || chain.length == 0)
		{
			return null;
		}
		ArrayList<byte[]> encodings = new ArrayList<>();
		for (Certificate cert : chain)
		{
			encodings.add(cert.getEncoded());
		}
		return encodings.toArray(new byte[0][]);
	}

	/** Called via JNI: the user's private key. */
	private PrivateKey getUserKey() throws Exception
	{
		KeyStore store = userKeyStore();
		String alias = store != null ? userKeyAlias(store) : null;
		if (alias == null)
		{
			return null;
		}
		String password = mProfile.getStringExtra(EXTRA_P12_PASSWORD);
		return (PrivateKey)store.getKey(alias, password != null ? password.toCharArray() : new char[0]);
	}

	public native boolean initializeCharon(BuilderAdapter builder, String logfile, String appdir, boolean byod, boolean ipv6);

	public native void deinitializeCharon();

	public native void initiate(String config);

	/**
	 * Adapter for VpnService.Builder which is used to access it safely via JNI.
	 * There is a corresponding C object to access it from native code.
	 */
	@Keep
	public class BuilderAdapter
	{
		private Intent mProfile;
		private VpnService.Builder mBuilder;
		private BuilderCache mCache;
		private BuilderCache mEstablishedCache;

		public synchronized void setProfile(Intent profile)
		{
			mProfile = profile;
			mBuilder = createBuilder();
			mCache = new BuilderCache(profile);
		}

		private VpnService.Builder createBuilder()
		{
			VpnService.Builder builder = new CharonVpnService.Builder();
			String name = mProfile.getStringExtra(EXTRA_NAME);
			builder.setSession(name != null ? name : "Tifusi");
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
			{
				builder.setMetered(false);
			}
			return builder;
		}

		public synchronized boolean addAddress(String address, int prefixLength)
		{
			try
			{
				mCache.addAddress(address, prefixLength);
			}
			catch (IllegalArgumentException ex)
			{
				return false;
			}
			return true;
		}

		public synchronized boolean addDnsServer(String address)
		{
			try
			{
				mCache.addDnsServer(address);
			}
			catch (IllegalArgumentException ex)
			{
				return false;
			}
			return true;
		}

		public synchronized boolean addRoute(String address, int prefixLength)
		{
			try
			{
				mCache.addRoute(address, prefixLength);
			}
			catch (IllegalArgumentException ex)
			{
				return false;
			}
			return true;
		}

		public synchronized boolean addSearchDomain(String domain)
		{
			try
			{
				mBuilder.addSearchDomain(domain);
			}
			catch (IllegalArgumentException ex)
			{
				return false;
			}
			return true;
		}

		public synchronized boolean setMtu(int mtu)
		{
			try
			{
				mCache.setMtu(mtu);
			}
			catch (IllegalArgumentException ex)
			{
				return false;
			}
			return true;
		}

		private synchronized ParcelFileDescriptor establishIntern()
		{
			ParcelFileDescriptor fd;
			try
			{
				mCache.applyData(mBuilder);
				fd = mBuilder.establish();
			}
			catch (Exception ex)
			{
				Log.e(TAG, "establishing the TUN device failed", ex);
				return null;
			}
			if (fd == null)
			{
				return null;
			}
			/* now that the TUN device is created we don't need the current
			 * builder anymore, but we might need another when reestablishing */
			mBuilder = createBuilder();
			mEstablishedCache = mCache;
			mCache = new BuilderCache(mProfile);
			return fd;
		}

		public synchronized int establish()
		{
			ParcelFileDescriptor fd = establishIntern();
			return fd != null ? keepCopy(fd).detachFd() : -1;
		}

		private ParcelFileDescriptor keepCopy(ParcelFileDescriptor fd)
		{
			closeTun();
			try
			{
				mTunCopy = fd.dup();
			}
			catch (Exception e)
			{
				Log.w(TAG, "could not keep a copy of the TUN descriptor", e);
			}
			return fd;
		}

		public synchronized void closeTun()
		{
			if (mTunCopy != null)
			{
				try
				{
					mTunCopy.close();
				}
				catch (Exception ignored)
				{
				}
				mTunCopy = null;
			}
		}

		public synchronized int establishNoDns()
		{
			ParcelFileDescriptor fd;

			if (mEstablishedCache == null)
			{
				return -1;
			}
			try
			{
				Builder builder = createBuilder();
				mEstablishedCache.applyData(builder);
				fd = builder.establish();
			}
			catch (Exception ex)
			{
				Log.e(TAG, "reestablishing the TUN device failed", ex);
				return -1;
			}
			if (fd == null)
			{
				return -1;
			}
			return keepCopy(fd).detachFd();
		}
	}

	/**
	 * Cache non DNS related information so we can recreate the builder without
	 * that information when reestablishing IKE_SAs
	 */
	public class BuilderCache
	{
		private final List<IPRange> mAddresses = new ArrayList<>();
		private final List<IPRange> mRoutesIPv4 = new ArrayList<>();
		private final List<IPRange> mRoutesIPv6 = new ArrayList<>();
		private final List<InetAddress> mDnsServers = new ArrayList<>();
		private int mMtu;
		private boolean mIPv4Seen, mIPv6Seen, mDnsServersConfigured;

		public BuilderCache(Intent profile)
		{
			String[] dns = profile.getStringArrayExtra(EXTRA_DNS);
			if (dns != null)
			{
				for (String server : dns)
				{
					try
					{
						mDnsServers.add(Utils.parseInetAddress(server));
						recordAddressFamily(server);
						mDnsServersConfigured = true;
					}
					catch (UnknownHostException e)
					{
						Log.w(TAG, "bad DNS server " + server);
					}
				}
			}
			mMtu = profile.getIntExtra(EXTRA_MTU, 1400);
		}

		public void addAddress(String address, int prefixLength)
		{
			try
			{
				mAddresses.add(new IPRange(address, prefixLength));
				recordAddressFamily(address);
			}
			catch (UnknownHostException ex)
			{
				Log.w(TAG, "bad address " + address);
			}
		}

		public void addDnsServer(String address)
		{
			/* ignore received DNS servers if any were configured */
			if (mDnsServersConfigured)
			{
				return;
			}
			try
			{
				mDnsServers.add(Utils.parseInetAddress(address));
				recordAddressFamily(address);
			}
			catch (UnknownHostException e)
			{
				Log.w(TAG, "bad DNS server " + address);
			}
		}

		public void addRoute(String address, int prefixLength)
		{
			try
			{
				if (isIPv6(address))
				{
					mRoutesIPv6.add(new IPRange(address, prefixLength));
				}
				else
				{
					mRoutesIPv4.add(new IPRange(address, prefixLength));
				}
			}
			catch (UnknownHostException ex)
			{
				Log.w(TAG, "bad route " + address);
			}
		}

		public void setMtu(int mtu)
		{
			mMtu = mtu;
		}

		public void recordAddressFamily(String address)
		{
			try
			{
				if (isIPv6(address))
				{
					mIPv6Seen = true;
				}
				else
				{
					mIPv4Seen = true;
				}
			}
			catch (UnknownHostException ex)
			{
				Log.w(TAG, "bad address " + address);
			}
		}

		public void applyData(Builder builder)
		{
			for (IPRange address : mAddresses)
			{
				builder.addAddress(address.getFrom(), address.getPrefix());
			}
			for (InetAddress server : mDnsServers)
			{
				builder.addDnsServer(server);
			}
			if (mIPv4Seen)
			{
				IPRangeSet ranges = new IPRangeSet();
				ranges.addAll(mRoutesIPv4);
				for (IPRange subnet : ranges.subnets())
				{
					try
					{
						builder.addRoute(subnet.getFrom(), subnet.getPrefix());
					}
					catch (IllegalArgumentException e)
					{	/* some Android versions don't like multicast addresses here */
						if (!subnet.getFrom().isMulticastAddress())
						{
							throw e;
						}
					}
				}
			}
			else
			{	/* allow traffic that would otherwise be blocked to bypass the VPN */
				builder.allowFamily(OsConstants.AF_INET);
			}
			if (mIPv6Seen)
			{
				IPRangeSet ranges = new IPRangeSet();
				ranges.addAll(mRoutesIPv6);
				for (IPRange subnet : ranges.subnets())
				{
					try
					{
						builder.addRoute(subnet.getFrom(), subnet.getPrefix());
					}
					catch (IllegalArgumentException e)
					{
						if (!subnet.getFrom().isMulticastAddress())
						{
							throw e;
						}
					}
				}
			}
			else
			{
				builder.allowFamily(OsConstants.AF_INET6);
			}
			builder.setMtu(mMtu);
		}

		private boolean isIPv6(String address) throws UnknownHostException
		{
			InetAddress addr = Utils.parseInetAddress(address);
			if (addr instanceof Inet4Address)
			{
				return false;
			}
			return addr instanceof Inet6Address;
		}
	}

	/**
	 * Function called via JNI to determine information about the Android version.
	 */
	private static String getAndroidVersion()
	{
		String version = "Android " + Build.VERSION.RELEASE + " - " + Build.DISPLAY;
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
		{
			version += "/" + Build.VERSION.SECURITY_PATCH;
		}
		return version;
	}

	/**
	 * Function called via JNI to determine information about the device.
	 */
	private static String getDeviceString()
	{
		return Build.MODEL + " - " + Build.BRAND + "/" + Build.PRODUCT + "/" + Build.MANUFACTURER;
	}
}
