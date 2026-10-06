package com.thiairo.lumen;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/**
 * Lumen - hospeda a interface em assets/ dentro de um WebView.
 *
 * O que este shell faz de nativo (e um navegador nao faria):
 *   - edge-to-edge de verdade, respeitando safe areas
 *   - cor da barra de status acompanha o tema
 *   - ponte JS com clipboard, estado de rede e informacoes do aparelho
 *   - botao voltar navega no historico do WebView
 *   - bloqueia navegacao para fora do pacote
 */
public class MainActivity extends Activity {

    private static final String TAG = "Lumen";
    private WebView web;
    private FrameLayout holder;
    private TextView splash;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().requestFeature(Window.FEATURE_NO_TITLE);

        // edge-to-edge: conteudo desenha atras das barras do sistema
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(Color.TRANSPARENT);
            getWindow().setNavigationBarColor(Color.TRANSPARENT);
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                  | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                  | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
        }

        buildViews();
        setContentView(holder);
        loadInterface();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void buildViews() {
        holder = new FrameLayout(this);
        holder.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // fundo na cor do tema, para nao piscar branco no carregamento
        holder.setBackgroundColor(escuro() ? 0xFF0A0E14 : 0xFFF7F9FC);

        splash = new TextView(this);
        splash.setText("Lumen");
        splash.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        splash.setTextColor(escuro() ? 0xFFA7B2C4 : 0xFF4A5768);
        splash.setGravity(Gravity.CENTER);
        holder.addView(splash, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        web = new WebView(this);
        web.setVisibility(View.INVISIBLE); // revelado no onPageFinished
        configure(web);
        holder.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private boolean escuro() {
        int modo = getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return modo == Configuration.UI_MODE_NIGHT_YES;
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configure(WebView w) {
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(false);
        s.setSupportZoom(false);          // a interface ja escala com o texto
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setDefaultTextEncodingName("utf-8");
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            s.setAllowUniversalAccessFromFileURLs(true);
        }
        // 16sp e a base; o layout foi desenhado nessa escala
        s.setDefaultFontSize(16);

        w.addJavascriptInterface(new Bridge(), "LumenNative");

        w.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, String url) {
                if (url == null) return true;
                if (url.startsWith("file:///android_asset/") || url.startsWith("about:")) {
                    return false;
                }
                // link externo: nao sai do app sem o usuario decidir
                copiar(url);
                toast("Link copiado (não abre fora): " + url);
                return true;
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                ui.postDelayed(new Runnable() {
                    @Override public void run() {
                        if (web != null) web.setVisibility(View.VISIBLE);
                        if (splash != null) splash.setVisibility(View.GONE);
                    }
                }, 90);
            }

            @Override
            public void onReceivedError(WebView v, int code, String desc, String url) {
                Log.e(TAG, "erro ao carregar: " + desc);
                if (splash != null) {
                    splash.setText("Falhou ao carregar:\n" + desc);
                    splash.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                }
            }
        });

        w.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView v, int progress) {
                if (splash != null && progress < 100 && splash.getVisibility() == View.VISIBLE) {
                    splash.setText("Lumen\n" + progress + "%");
                }
            }
        });
    }

    private void loadInterface() {
        web.loadUrl("file:///android_asset/index.html");
    }

    private boolean online() {
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            NetworkInfo n = cm.getActiveNetworkInfo();
            return n != null && n.isConnected();
        } catch (Exception e) {
            return false;
        }
    }

    private void toast(final String msg) {
        ui.post(new Runnable() {
            @Override public void run() {
                android.widget.Toast.makeText(MainActivity.this, msg,
                        android.widget.Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void copiar(String txt) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("lumen", txt));
        } catch (Exception e) {
            Log.w(TAG, "clipboard: " + e.getMessage());
        }
    }

    /** Exposto ao JavaScript como window.LumenNative */
    public final class Bridge {

        @JavascriptInterface
        public void toast(final String msg) {
            MainActivity.this.toast(msg);
        }

        @JavascriptInterface
        public void copy(String txt) {
            copiar(txt);
        }

        @JavascriptInterface
        public boolean online() {
            return MainActivity.this.online();
        }

        @JavascriptInterface
        public String info() {
            return Build.MANUFACTURER + " " + Build.MODEL
                    + " · API " + Build.VERSION.SDK_INT
                    + " · " + Locale.getDefault().getDisplayLanguage();
        }

        @JavascriptInterface
        public boolean dark() {
            return escuro();
        }

        @JavascriptInterface
        public void reload() {
            ui.post(new Runnable() {
                @Override public void run() {
                    if (web != null) web.loadUrl("file:///android_asset/index.html");
                }
            });
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && web != null && web.canGoBack()) {
            web.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (web != null) web.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            ViewGroup p = (ViewGroup) web.getParent();
            if (p != null) p.removeView(web);
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
