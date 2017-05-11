package com.teskalabs.cvio;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.OrientationEventListener;

import com.teskalabs.seacat.android.client.SeaCatClient;
import com.teskalabs.seacat.android.client.socket.SocketConfig;
import com.teskalabs.cvio.inapp.InAppInputManager;
import com.teskalabs.cvio.inapp.KeySym;

import java.io.IOException;
import java.nio.ByteBuffer;

public class CatVision extends ContextWrapper implements VNCDelegate {

	private static final String TAG = CatVision.class.getName();

	public static final String DEFAULT_CLIENT_HANDLE = "DefaultClientHandle";

	protected static CVIOSeaCatPlugin cvioSeaCatPlugin = null;
	private static final int port = 5900;
	private static double downscale = 0;

	private MediaProjectionManager mProjectionManager = null;
	private static MediaProjection sMediaProjection = null;
	private static Thread sCaptureThread = null;

	private ImageReader mImageReader = null;
	private Handler mHandler = null;

	private VirtualDisplay mVirtualDisplay = null;
	private static final int VIRTUAL_DISPLAY_FLAGS = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY | DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC;

	private int mDensity;
	private Display mDisplay = null;

	private int mRotation;
	private OrientationChangeCallback mOrientationChangeCallback = null;

	private final VNCServer vncServer;
	private final InAppInputManager inputManager;

	private final String publicAccessKey;

	///

	static protected CatVision instance = null;

	public synchronized static CatVision initialize(Application app) {

		if (instance != null) throw new RuntimeException("Already initialized");
		instance = new CatVision(app);

		if (cvioSeaCatPlugin == null)
		{
			cvioSeaCatPlugin = new CVIOSeaCatPlugin(port);
		}

		return instance;
	}

	static public CatVision getInstance()
	{
		return instance;
	}

	///

	protected CatVision(Application app) {
		super(app.getApplicationContext());

		publicAccessKey = getMetaData(app.getApplicationContext(), "cvio.public_access_key");
		if (publicAccessKey == null)
		{
			throw new RuntimeException("CatVision access key (cvio.public_access_key) not provided");
		}

		cviojni.set_delegate(this);
		vncServer = new VNCServer(this);
		inputManager = new InAppInputManager(app);

		try {
			SeaCatClient.configureSocket(
				port,
				SocketConfig.Domain.AF_UNIX, SocketConfig.Type.SOCK_STREAM, 0,
				vncServer.getSocketFileName(), ""
			);
		} catch (IOException e) {
			Log.e(TAG, "SeaCatClient expcetion", e);
		}

	}

	///

	public void setClientHandle(String clientHandle)
	{
	}

	///

	public void requestStart(Activity activity, int requestCode) {
		// call for the projection manager
		mProjectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);

		if (sCaptureThread == null) {
			// start capture handling thread
			sCaptureThread = new Thread(new Runnable() {
				public void run() {
					Looper.prepare();
					mHandler = new Handler();
					Looper.loop();
				}
			});
			sCaptureThread.start();
		}

		try {
			SeaCatClient.connect();
		} catch (IOException e) {
			Log.e(TAG, "SeaCatClient expcetion", e);
		}

		activity.startActivityForResult(mProjectionManager.createScreenCaptureIntent(), requestCode);
	}

	public void stop() {
		if (sCaptureThread == null) return;
		if (mHandler == null) return;

		mHandler.post(new Runnable() {
			@Override
			public void run() {
				if (sMediaProjection != null) {
					sMediaProjection.stop();
				}
			}
		});

		vncServer.stop();
		stopRepeatingPing();
	}

	public boolean isStarted() {
		return (sMediaProjection != null);
	}

	public void onActivityResult(Activity activity, int resultCode, Intent data) {
		sMediaProjection = mProjectionManager.getMediaProjection(resultCode, data);

		if (sMediaProjection != null) {
			// display metrics
			DisplayMetrics metrics = getResources().getDisplayMetrics();
			mDensity = metrics.densityDpi;
			mDisplay = activity.getWindowManager().getDefaultDisplay();

			if (downscale == 0) {
				if (mDensity < 150) {
					downscale = 1;
				} else if (mDensity < 300) {
					downscale = 2;
				} else {
					downscale = 4;
				}
			}

			// create virtual display depending on device width / height
			createVirtualDisplay();

			// register orientation change callback
			mOrientationChangeCallback = new OrientationChangeCallback(this);
			if (mOrientationChangeCallback.canDetectOrientation()) {
				mOrientationChangeCallback.enable();
			}

			// register media projection stop callback
			sMediaProjection.registerCallback(new MediaProjectionStopCallback(), mHandler);
		}
	}

	/******************************************
	 * Here we receive Images
	 ****************/

	private class ImageAvailableListener implements ImageReader.OnImageAvailableListener {
		@Override
		public void onImageAvailable(ImageReader reader) {
			cviojni.image_ready();
		}
	}

	@Override
	public void takeImage() {
		//TODO: Consider synchronisation
		if (mImageReader == null) return;

		Image image = null;
		try {
			image = mImageReader.acquireLatestImage();
			if (image != null) {
				Image.Plane[] planes = image.getPlanes();
				ByteBuffer b = planes[0].getBuffer();
				// planes[0].getPixelStride() has to be 4 (32 bit)
				cviojni.push_pixels(b, planes[0].getRowStride());
			}
		} catch (Exception e) {
			Log.e(TAG, "ImageReader", e);
		} finally {
			if (image != null) {
				image.close();
			}
		}
	}

	/******************************************
	 * Stopping media projection
	 ****************/

	private class MediaProjectionStopCallback extends MediaProjection.Callback {
		@Override
		public void onStop() {
			mHandler.post(new Runnable() {
				@Override
				public void run() {
					if (mVirtualDisplay != null) mVirtualDisplay.release();
					if (mImageReader != null) mImageReader.setOnImageAvailableListener(null, null);
					if (mOrientationChangeCallback != null) mOrientationChangeCallback.disable();
					sMediaProjection.unregisterCallback(MediaProjectionStopCallback.this);
					sMediaProjection = null;

					vncServer.stop();
					stopRepeatingPing();
				}
			});
		}
	}


	/******************************************
	 * Orientation change listener
	 ****************/

	private class OrientationChangeCallback extends OrientationEventListener {
		OrientationChangeCallback(Context context) {
			super(context);
		}

		@Override
		public void onOrientationChanged(int orientation) {
			synchronized (this) {
				final int rotation = mDisplay.getRotation();
				if (rotation != mRotation) {
					mRotation = rotation;
					try {
						// clean up
						if (mVirtualDisplay != null) mVirtualDisplay.release();
						if (mImageReader != null)
							mImageReader.setOnImageAvailableListener(null, null);

						// re-create virtual display depending on device width / height
						createVirtualDisplay();
					} catch (Exception e) {
						e.printStackTrace();
					}
				}
			}
		}
	}

	/******************************************
	 * Factoring Virtual Display creation
	 ****************/
	private void createVirtualDisplay() {
		// get width and height
		Point size = new Point();
		mDisplay.getRealSize(size);
		int mWidth = (int)(size.x / downscale);
		int mHeight = (int)(size.y / downscale);

		vncServer.stop();
		vncServer.start(mWidth, mHeight);
		startRepeatingPing();

		// start capture reader
		mImageReader = ImageReader.newInstance(mWidth, mHeight, PixelFormat.RGBA_8888, 2);
		mVirtualDisplay = sMediaProjection.createVirtualDisplay("cviodisplay", mWidth, mHeight, mDensity, VIRTUAL_DISPLAY_FLAGS, mImageReader.getSurface(), null, mHandler);
		mImageReader.setOnImageAvailableListener(new ImageAvailableListener(), mHandler);
	}


	/******************************************
	 * SeaCat Gateway ping
	 ****************/

	Runnable mStatusChecker = new Runnable() {
		@Override
		public void run() {
			try {
				SeaCatClient.ping();
			} catch (IOException e) {
				// No-op
			} finally {
				mHandler.postDelayed(mStatusChecker, 30 * 1000); // Ensure that we are connected every 30 seconds
			}
		}
	};

	protected void startRepeatingPing() {
		mHandler.removeCallbacks(mStatusChecker);
		mStatusChecker.run();
	}

	void stopRepeatingPing() {
		mHandler.removeCallbacks(mStatusChecker);
	}

	/******************************************
	 * Input support
	 ****************/

	@Override
	public void rfbKbdAddEventProc(boolean down, long keySymCode, String client) {
		KeySym ks = KeySym.lookup.get((int) keySymCode);
		inputManager.onKeyboardEvent(down, ks);
	}

	@Override
	public void rfbKbdReleaseAllKeysProc(String client) {
		Log.i(TAG, "rfbKbdReleaseAllKeysProc: client:"+client);
	}

	///

	@Override
	public void rfbPtrAddEventProc(int buttonMask, int x, int y, String client) {
		inputManager.onMouseEvent(buttonMask, (int)(x * downscale), (int)(y * downscale));
	}

	///

	@Override
	public void rfbSetXCutTextProc(String text, String client) {
		Log.i(TAG, "rfbSetXCutTextProc: text:"+text+" client:"+client);
	}

	@Override
	public int rfbNewClientHook(String client) {
		Log.i(TAG, "New VNC client:"+client);
		return 0;
	}

	public static String getMetaData(Context context, String name) {
		try {
			ApplicationInfo ai = context.getPackageManager().getApplicationInfo(context.getPackageName(), PackageManager.GET_META_DATA);
			Bundle bundle = ai.metaData;
			return bundle.getString(name);
		} catch (PackageManager.NameNotFoundException e) {
			Log.e(TAG, "Unable to load meta-data: " + e.getMessage());
		}
		return null;
	}
}
