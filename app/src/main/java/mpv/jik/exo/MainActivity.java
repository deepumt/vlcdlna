package mpv.jik.exo;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.StringReader;
import java.util.ArrayList;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;

import tv.dlna.DmrCallback;
import tv.dlna.DmrConstants;
import tv.dlna.DmrNative;

public class MainActivity extends Activity implements SurfaceHolder.Callback, MediaPlayer.EventListener {

    private SurfaceView mSurfaceView;
    private TextView mStatusText;
    private ProgressBar mLoadingBar;
    private TextView mTitleOverlay;
    private SurfaceHolder mSurfaceHolder;

    private Handler mOverlayHandler = new Handler(Looper.getMainLooper());
    private Runnable mOverlayHideRunnable = new Runnable() {
        @Override
        public void run() {
            if (mTitleOverlay != null) {
                mTitleOverlay.setVisibility(View.GONE);
            }
        }
    };

    private LibVLC mLibVLC;
    private MediaPlayer mMediaPlayer;

    private long mUpnpHandle = 0;
    private long mRendererHandle = 0;

    private boolean mProgressReporting = false;
    private Thread mProgressThread;

    private Handler mMainHandler;
    private String mCurrentUri = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mMainHandler = new Handler(Looper.getMainLooper());
        initUI();
        initVLC();
        initDLNA();
    }

    // ==================== UI ====================

    private void initUI() {
        mSurfaceView = (SurfaceView) findViewById(R.id.surface_view);
        mStatusText = (TextView) findViewById(R.id.status_text);
        mLoadingBar = (ProgressBar) findViewById(R.id.loading_bar);
        mTitleOverlay = (TextView) findViewById(R.id.title_overlay);
        mSurfaceHolder = mSurfaceView.getHolder();
        mSurfaceHolder.addCallback(this);

        // ★ 每个 TextView 各自加载不同字体文件
        try {
            mStatusText.setTypeface(Typeface.createFromAsset(getAssets(), "hrtfs/mia.ttf"));
        } catch (Exception e) {
            e.printStackTrace();
        }

        try {
            mTitleOverlay.setTypeface(Typeface.createFromAsset(getAssets(), "hrtfs/mia.ttf"));
            // ↑ 把 xxx.ttf 换成你飘窗实际要用的字体文件名
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void showTitleOverlay(String videoTitle) {
        if (mTitleOverlay == null) return;
        mOverlayHandler.removeCallbacks(mOverlayHideRunnable);
        mTitleOverlay.setText("投播：" + videoTitle);
        mTitleOverlay.setVisibility(View.VISIBLE);
        mOverlayHandler.postDelayed(mOverlayHideRunnable, 15_000);
    }

    private void hideTitleOverlay() {
        mOverlayHandler.removeCallbacks(mOverlayHideRunnable);
        if (mTitleOverlay != null) {
            mTitleOverlay.setVisibility(View.GONE);
        }
    }

    private void setStatus(final String text) {
        mMainHandler.post(new Runnable() {
				@Override
				public void run() {
					mStatusText.setText(text);
				}
			});
    }

    private void showVideoUI(final boolean show) {
        mMainHandler.post(new Runnable() {
				@Override
				public void run() {
					mSurfaceView.setVisibility(show ? View.VISIBLE : View.GONE);
					mStatusText.setVisibility(show ? View.GONE : View.VISIBLE);
					mLoadingBar.setVisibility(View.GONE);
					if (!show) hideTitleOverlay();
				}
			});
    }

    private void showLoading(final boolean show) {
        mMainHandler.post(new Runnable() {
				@Override
				public void run() {
					mLoadingBar.setVisibility(show ? View.VISIBLE : View.GONE);
				}
			});
    }

    private void showToast(final String msg) {
        mMainHandler.post(new Runnable() {
				@Override
				public void run() {
					Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show();
				}
			});
    }

    // ==================== VLC ====================

    private void initVLC() {
        ArrayList<String> options = new ArrayList<String>();

        options.add("--network-caching=1500");
        options.add("--live-caching=1500");
        options.add("--clock-jitter=0");
        options.add("--clock-synchro=0");
        options.add("--rtsp-tcp");
        options.add("--avcodec-fast");
        options.add("--drop-late-frames");
        options.add("--skip-frames");
        options.add("--aout=opensles");
        options.add("--audio-time-stretch");
        options.add("--no-sub-autodetect-file");
        options.add("--codec=mediacodec,all");

        mLibVLC = new LibVLC(this, options);
        mMediaPlayer = new MediaPlayer(mLibVLC);
        mMediaPlayer.setEventListener(this);
    }

    // ==================== DLNA ====================

    private void initDLNA() {
        setStatus("正在初始化投屏服务...");

        new Thread(new Runnable() {
				@Override
				public void run() {
					mUpnpHandle = DmrNative.upnpCreate();
					if (mUpnpHandle == 0) {
						setStatus("UPnP 创建失败");
						return;
					}

					int ret = DmrNative.upnpStart(mUpnpHandle);
					if (ret != 0) {
						setStatus("UPnP 启动失败");
						return;
					}

					mRendererHandle = DmrNative.rendererCreate(
                        mUpnpHandle,
                        "dlna-vlc",
                        null,
                        "VlC-DLNA",
                        "DMR-1.0",
                        mDmrCallback
					);

					if (mRendererHandle == 0) {
						setStatus("渲染器创建失败");
						return;
					}

					ret = DmrNative.rendererStart(mRendererHandle);
					if (ret != 0) {
						setStatus("渲染器启动失败");
						return;
					}

					setStatus("DMR已启动_等待投屏推送...");
				}
			}).start();
    }

    // ==================== DIDL 解析 ====================

    private static class DidlInfo {
        String url = null;
        String title = "未知视频";
    }

    private DidlInfo parseDidlInfo(String rawXml) {
        DidlInfo info = new DidlInfo();
        if (rawXml == null || rawXml.length() == 0) return info;

        try {
            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            factory.setNamespaceAware(true);
            XmlPullParser parser = factory.newPullParser();
            parser.setInput(new StringReader(rawXml));

            int eventType = parser.getEventType();
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    String tagName = parser.getName();
                    if ("res".equals(tagName)) {
                        info.url = parser.nextText().trim();
                    } else if ("title".equals(tagName)) {
                        String t = parser.nextText().trim();
                        if (t.length() > 0) {
                            info.title = t;
                        }
                    }
                }
                eventType = parser.next();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return info;
    }

    // ==================== DLNA 回调 ====================

    private DmrCallback mDmrCallback = new DmrCallback() {

        @Override
        public void onSetAVTransportURI(final String uri, final String rawDidlLiteXml) {

            mMainHandler.post(new Runnable() {
					@Override
					public void run() {
						hideTitleOverlay();
					}
				});

            DidlInfo info = parseDidlInfo(rawDidlLiteXml);

            String resolvedUrl = info.url;
            if (resolvedUrl == null || resolvedUrl.length() == 0) {
                resolvedUrl = uri;
            }
            if (resolvedUrl == null || resolvedUrl.length() == 0) {
                mMainHandler.post(new Runnable() {
						@Override
						public void run() {
							showToast("无效的播放地址");
							setStatus("等待投屏...");
							showVideoUI(false);
						}
					});
                return;
            }

            mCurrentUri = resolvedUrl;
            final String finalUrl = resolvedUrl;
            final String videoTitle = info.title;

            mMainHandler.post(new Runnable() {
					@Override
					public void run() {
						showVideoUI(true);
						showLoading(true);
						setStatus("正在加载:\n" + finalUrl);
						startVLCPlayback(finalUrl);
						showTitleOverlay(videoTitle);
					}
				});
        }

        @Override
        public void onPlay(String speed) {
            mMainHandler.post(new Runnable() {
					@Override
					public void run() {
						if (mMediaPlayer != null) mMediaPlayer.play();
					}
				});
        }

        @Override
        public void onPause() {
            mMainHandler.post(new Runnable() {
					@Override
					public void run() {
						if (mMediaPlayer != null) mMediaPlayer.pause();
					}
				});
        }

        @Override
        public void onStop() {
            mMainHandler.post(new Runnable() {
					@Override
					public void run() {
						stopProgressReporting();
						stopVLCPlayback();
						showVideoUI(false);
						setStatus("等待投屏...");
					}
				});
        }

        @Override
        public void onSeek(final String unit, final String target) {
            if (target == null) return;
            final long ms = DmrNative.hmsToMs(target);
            if (ms <= 0 || mMediaPlayer == null) return;

            long duration = mMediaPlayer.getLength();
            if (duration > 0 && ms > duration) return;

            mMainHandler.post(new Runnable() {
					@Override
					public void run() {
						mMediaPlayer.setTime(ms);
						reportPositionOnce();
					}
				});
        }

        @Override public void onNext() { }
        @Override public void onPrevious() { }
        @Override public void onSetPlayMode(String mode) { }
        @Override public void onSetVolumeDB(String channel, int volumeDB) { }

        @Override
        public void onSetVolume(final String channel, final int volume) {
            if (mMediaPlayer == null) return;
            mMainHandler.post(new Runnable() {
					@Override
					public void run() {
						int v = Math.max(0, Math.min(100, volume));
						mMediaPlayer.setVolume(v);
					}
				});
        }

        @Override
        public void onSetMute(final String channel, final boolean mute) {
            if (mMediaPlayer == null) return;
            mMainHandler.post(new Runnable() {
					@Override
					public void run() {
						mMediaPlayer.setVolume(mute ? 0 : 100);
					}
				});
        }
    };

    // ==================== 进度上报 ====================

    private void reportPositionOnce() {
        if (mMediaPlayer == null || mRendererHandle == 0) return;
        long position = mMediaPlayer.getTime();
        long duration = mMediaPlayer.getLength();
        if (duration <= 0) return;
        String posStr = DmrNative.msToHms(position);
        String durStr = DmrNative.msToHms(duration);
        DmrNative.rendererUpdatePosition(mRendererHandle, 0, posStr, durStr);
    }

    // ==================== VLC 播放 ====================

    private void startVLCPlayback(String url) {
        try {
            releasePlayerMedia();

            Media media = new Media(mLibVLC, android.net.Uri.parse(url));
            String lower = url.toLowerCase();

            media.addOption(":network-caching=1500");
            media.addOption(":live-caching=1500");
            media.addOption(":file-caching=1500");

            if (lower.contains(".m3u8") || lower.contains("hls")) {
                media.addOption(":network-caching=3000");
                media.addOption(":live-caching=3000");
                media.addOption(":http-reconnect=true");
            } else if (url.startsWith("rtsp://")) {
                media.addOption(":rtsp-tcp");
                media.addOption(":network-caching=1000");
                media.addOption(":live-caching=1000");
            } else if (url.startsWith("rtmp://") || lower.contains("rtmp")) {
                media.addOption(":network-caching=2000");
            } else if (url.startsWith("udp://") || url.startsWith("rtp://")) {
                media.addOption(":network-caching=300");
                media.addOption(":live-caching=300");
            } else if (url.startsWith("http://") || url.startsWith("https://")) {
                media.addOption(":http-reconnect=true");
                media.addOption(":network-caching=2000");
            }

            mMediaPlayer.setMedia(media);
            media.release();

            if (mSurfaceHolder != null && mSurfaceHolder.getSurface().isValid()) {
                mMediaPlayer.getVLCVout().setVideoView(mSurfaceView);
                mMediaPlayer.getVLCVout().attachViews();
            }

            mMediaPlayer.play();
            startProgressReporting();

        } catch (Exception e) {
            e.printStackTrace();
            setStatus("播放失败:\n" + e.getMessage());
        }
    }

    private void stopVLCPlayback() {
        stopProgressReporting();
        if (mMediaPlayer != null) mMediaPlayer.stop();
        mCurrentUri = "";
    }

    private void releasePlayerMedia() {
        if (mMediaPlayer != null && mMediaPlayer.getMedia() != null) {
            mMediaPlayer.stop();
            mMediaPlayer.setMedia(null);
        }
    }

    // ==================== 进度轮询 ====================

    private void startProgressReporting() {
        stopProgressReporting();
        mProgressReporting = true;
        mProgressThread = new Thread(new Runnable() {
				@Override
				public void run() {
					while (mProgressReporting && mRendererHandle != 0) {
						try {
							Thread.sleep(800);
						} catch (InterruptedException e) {
							break;
						}
						if (mMediaPlayer == null) break;

						long position = mMediaPlayer.getTime();
						long duration = mMediaPlayer.getLength();
						if (duration <= 0) continue;

						final String posStr = DmrNative.msToHms(position);
						final String durStr = DmrNative.msToHms(duration);

						mMainHandler.post(new Runnable() {
								@Override
								public void run() {
									if (mRendererHandle != 0) {
										DmrNative.rendererUpdatePosition(mRendererHandle, 0, posStr, durStr);
									}
								}
							});
					}
				}
			});
        mProgressThread.start();
    }

    private void stopProgressReporting() {
        mProgressReporting = false;
        if (mProgressThread != null) {
            mProgressThread.interrupt();
            mProgressThread = null;
        }
    }

    // ==================== VLC 事件 ====================

    @Override
    public void onEvent(MediaPlayer.Event event) {
        switch (event.type) {

            case MediaPlayer.Event.Playing:
                mMainHandler.post(new Runnable() {
						@Override
						public void run() {
							showLoading(false);
							setStatus("");
						}
					});
                reportPositionOnce();
                if (mRendererHandle != 0) {
                    DmrNative.rendererUpdateTransportState(mRendererHandle, DmrConstants.STATE_PLAYING);
                }
                break;

            case MediaPlayer.Event.Paused:
                reportPositionOnce();
                if (mRendererHandle != 0) {
                    DmrNative.rendererUpdateTransportState(mRendererHandle, DmrConstants.STATE_PAUSED_PLAYBACK);
                }
                break;

            case MediaPlayer.Event.Stopped:
                if (mRendererHandle != 0) {
                    DmrNative.rendererUpdateTransportState(mRendererHandle, DmrConstants.STATE_STOPPED);
                }
                break;

            case MediaPlayer.Event.EndReached:
                if (mRendererHandle != 0) {
                    DmrNative.rendererUpdateTransportState(mRendererHandle, DmrConstants.STATE_NO_MEDIA_PRESENT);
                }
                mMainHandler.post(new Runnable() {
						@Override
						public void run() {
							showVideoUI(false);
							setStatus("播放完成\n\n等待投屏...");
						}
					});
                stopProgressReporting();
                break;

            case MediaPlayer.Event.EncounteredError:
                if (mRendererHandle != 0) {
                    DmrNative.rendererUpdateTransportState(mRendererHandle, DmrConstants.STATE_STOPPED);
                }
                mMainHandler.post(new Runnable() {
						@Override
						public void run() {
							setStatus("播放错误\n\n等待投屏...");
						}
					});
                stopProgressReporting();
                break;

            case MediaPlayer.Event.Buffering:
                final float buf = event.getBuffering();
                mMainHandler.post(new Runnable() {
						@Override
						public void run() {
							if (buf < 100f) showLoading(true);
							else showLoading(false);
						}
					});
                break;

            default:
                break;
        }
    }

    // ==================== Surface ====================

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        if (mMediaPlayer != null && mMediaPlayer.getVLCVout() != null) {
            mMediaPlayer.getVLCVout().setVideoView(mSurfaceView);
            mMediaPlayer.getVLCVout().attachViews();
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        if (mMediaPlayer != null && mMediaPlayer.getVLCVout() != null) {
            mMediaPlayer.getVLCVout().setWindowSize(width, height);
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        if (mMediaPlayer != null && mMediaPlayer.getVLCVout() != null) {
            mMediaPlayer.getVLCVout().detachViews();
        }
    }

    // ==================== 生命周期 ====================

    @Override
    protected void onDestroy() {
        stopProgressReporting();
        hideTitleOverlay();

        if (mMediaPlayer != null) {
            mMediaPlayer.release();
            mMediaPlayer = null;
        }
        if (mLibVLC != null) {
            mLibVLC.release();
            mLibVLC = null;
        }
        if (mRendererHandle != 0) {
            DmrNative.rendererStop(mRendererHandle);
            DmrNative.rendererDestroy(mRendererHandle);
            mRendererHandle = 0;
        }
        if (mUpnpHandle != 0) {
            DmrNative.upnpStop(mUpnpHandle);
            DmrNative.upnpDestroy(mUpnpHandle);
            mUpnpHandle = 0;
        }

        super.onDestroy();
    }
}
