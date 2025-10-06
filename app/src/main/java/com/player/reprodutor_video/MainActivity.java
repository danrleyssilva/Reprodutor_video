package com.player.reprodutor_video;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.text.InputType;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.content.Intent;


import androidx.appcompat.app.AppCompatActivity;

import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.toolbox.JsonObjectRequest;
import com.android.volley.toolbox.Volley;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.ui.PlayerView;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;

public class MainActivity extends AppCompatActivity {

    private static final Logger log = LoggerFactory.getLogger(MainActivity.class);
    private PlayerView playerView;
    private ExoPlayer player;
    private ProgressBar progressBar;
    private TextView tvUrl;
    private WebSocketClient webSocketClient;
    private Handler handler = new Handler();
    private Runnable sendProgressRunnable;
    private String currentVideoUrl = "";
    private String deviceId = "";
    private boolean isMaster = false;
    private String ip_ws = "";
    private String WEBSOCKET_URL = "";
    private String url_base_acess = "http://192.168.1.167/api_reprodutor/get_video.php?codigo=";

    // private static final String API_URL = "http://10.0.2.2/api_reprodutor/get_video.php?codigo=disp_01";
    // private static final String API_URL = "http://192.168.0.106/api_reprodutor/get_video.php?codigo=disp_02T";
    // private static final String API_URL = "http://192.168.0.106/api_reprodutor/get_video.php?codigo=disp_03T";
    // private static final String WEBSOCKET_URL = "ws://10.20.70.90:8080";

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        playerView = findViewById(R.id.playerView);
        progressBar = findViewById(R.id.progressBar);
        tvUrl = findViewById(R.id.tvUrl);

        progressBar.setVisibility(View.VISIBLE);
        RequestQueue queue = Volley.newRequestQueue(this);

        FloatingActionButton btnSetId = findViewById(R.id.BtnSetDevice);
        btnSetId.setOnClickListener(view -> {
            final EditText input = new EditText(MainActivity.this);
            input.setInputType(InputType.TYPE_CLASS_TEXT);
            input.setHint("Digite o identificador do dispositivo");

            // Preenche o EditText com o valor já salvo, se existir
            String savedId = loadDeviceId();
            if (!savedId.isEmpty()) {
                input.setText(savedId);
            }

            new AlertDialog.Builder(MainActivity.this).setTitle("Dispositivo").setView(input).setPositiveButton("OK", (dialog, which) -> {
                deviceId = input.getText().toString();
                saveDeviceId(deviceId);
                Toast.makeText(MainActivity.this, "Dispositivo salvo: " + deviceId, Toast.LENGTH_SHORT).show();

                requestVideo(deviceId);
            }).setNegativeButton("Cancelar", null).show();
        });


        deviceId = loadDeviceId();
        if (!deviceId.isEmpty()) {
            requestVideo(deviceId);
        } else {
            btnSetId.performClick();
        }

        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
        );

    }

    private void setupExoPlayer(String videoUrl, String grupo) {
        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);

        currentVideoUrl = videoUrl;
        MediaItem mediaItem = MediaItem.fromUri(Uri.parse(videoUrl));
        player.setMediaItem(mediaItem);
        player.setRepeatMode(Player.REPEAT_MODE_ALL);
        player.prepare();
        player.setPlayWhenReady(true);

        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    progressBar.setVisibility(View.GONE);
                    startSendingProgress(grupo);
                }
            }

            @Override
            public void onPlayerError(com.google.android.exoplayer2.PlaybackException error) {
                Log.e("ExoPlayer", "Erro no player: " + error.getMessage());
            }
        });
    }

    private void startSendingProgress(String grupo) {
        if (!isMaster) return; // só master envia

        sendProgressRunnable = new Runnable() {
            @Override
            public void run() {
                if (webSocketClient != null && webSocketClient.isOpen() && player != null) {
                    try {
                        JSONObject progress = new JSONObject();

                        long duration = player.getDuration();
                        progress.put("command", "progress");
                        progress.put("position", player.getCurrentPosition());
                        if (duration == com.google.android.exoplayer2.C.TIME_UNSET) {
                            duration = 0;
                        }
                        progress.put("duration", duration);
                        progress.put("grupo", grupo);
                        webSocketClient.send(progress.toString());
                        Log.d("WebSocket", "Progress enviado: " + progress);
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }
                }
                handler.postDelayed(this, 5000);
            }
        };
        handler.post(sendProgressRunnable);
    }

    private void connectWebSocket(String videoUrl, String grupo) {
        try {
            URI uri = new URI(WEBSOCKET_URL);
            webSocketClient = new WebSocketClient(uri) {
                @Override
                public void onOpen(ServerHandshake handshake) {
                    Log.d("WebSocket", "Conectado ao servidor!");
                    try {
                        JSONObject ready = new JSONObject();
                        ready.put("command", "ready");
                        ready.put("url", videoUrl);
                        ready.put("grupo", grupo);
                        send(ready.toString());
                        Log.d("WebSocket", "Mensagem READY enviada: " + ready.toString());
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }
                }

                @Override
                public void onMessage(String message) {
                    Log.d("WebSocket", "Mensagem recebida: " + message);
                    runOnUiThread(() -> {
                        try {
                            JSONObject json = new JSONObject(message);
                            String action = json.optString("action");

                            if ("play".equals(action)) {
                                handlePlay(json);
                            } else if ("sync".equals(action)) {
//                                handleSync(json);
                            } else if ("reloadAll".equals(action)) {
                                Log.d("StartAll", "reiniciar os vídeos do grupo");
                                if (player != null) {
                                    player.seekTo(0, 0);
                                    player.setPlayWhenReady(true);
                                }
                            } else if ("updatePlaylist".equals(action)) {
//                                requestVideo(deviceId);
                                runOnUiThread(() -> restartApp());

                            } else if ("setMaster".equals(action)) {
                                isMaster = json.optBoolean("status", false);
                                if (isMaster) {
//                                    Toast.makeText(MainActivity.this, "Você agora é MASTER do grupo!", Toast.LENGTH_SHORT).show();
                                    startSendingProgress(grupo); // inicia envio de progress
                                } else {
                                    // se perdeu status de master
                                    handler.removeCallbacks(sendProgressRunnable);
                                }
                            }

                        } catch (JSONException e) {
                            e.printStackTrace();
                        }
                    });
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    Log.d("WebSocket", "Conexão fechada: " + reason);
                }

                @Override
                public void onError(Exception ex) {
                    Log.e("WebSocket", "Erro: " + ex.getMessage());
                    ex.printStackTrace();
                }
            };

            webSocketClient.connect();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void handlePlay(JSONObject json) throws JSONException {
        String url = json.getString("url");
        long startTimestamp = json.getLong("startTimestamp");

        // Se o vídeo for diferente do atual, troca o MediaItem
        if (!currentVideoUrl.equals(url)) {
            MediaItem mediaItem = MediaItem.fromUri(Uri.parse(url));
            player.setMediaItem(mediaItem);
            player.prepare();
            currentVideoUrl = url;
        }

        long now = System.currentTimeMillis();
        long delay = Math.max(0, startTimestamp - now);

        player.seekTo(0);
        playerView.postDelayed(() -> player.setPlayWhenReady(true), delay);

        playerView.setControllerShowTimeoutMs(0); // 0 = nunca esconder

    }

    private void handleSync(JSONObject json) throws JSONException {
        String url = json.getString("url");
        long position = json.getLong("position");

        // Se o vídeo for diferente do atual, troca o MediaItem
        if (!currentVideoUrl.equals(url)) {
            MediaItem mediaItem = MediaItem.fromUri(Uri.parse(url));
            player.setMediaItem(mediaItem);
            player.prepare();
            currentVideoUrl = url;
        }

        // Apenas sincroniza a posição, não reinicia quem já está tocando
        long currentPosition = player.getCurrentPosition();
        if (Math.abs(currentPosition - position) > 500) { // evita pequenos ajustes
            player.setPlayWhenReady(false);
            player.seekTo(position);
            player.setPlayWhenReady(true);
        }

        //  playerView.setControllerShowTimeoutMs(0); // 0 = nunca esconder
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (player != null) player.release();
        if (webSocketClient != null) webSocketClient.close();
        if (handler != null && sendProgressRunnable != null)
            handler.removeCallbacks(sendProgressRunnable);
    }

    private void saveDeviceId(String id) {
        getSharedPreferences("app_prefs", MODE_PRIVATE).edit().putString("device_id", id).apply();
    }

    private String loadDeviceId() {
        return getSharedPreferences("app_prefs", MODE_PRIVATE).getString("device_id", "");
    }

    private void requestVideo(String deviceId) {
        String apiUrl = url_base_acess + deviceId;
        progressBar.setVisibility(View.VISIBLE);

        RequestQueue queue = Volley.newRequestQueue(this);
        JsonObjectRequest request = new JsonObjectRequest(Request.Method.GET, apiUrl, null, response -> {
            try {
                ip_ws = response.getString("ip_ws");
                WEBSOCKET_URL = "ws://" + ip_ws + ":8080";

                JSONArray videosArray = response.getJSONArray("videos");
                if (videosArray.length() == 0) {
                    Toast.makeText(this, "Nenhum vídeo disponível", Toast.LENGTH_SHORT).show();
                    progressBar.setVisibility(View.GONE);
                    return;
                }

                // Monta a playlist
                player = new ExoPlayer.Builder(this).build();
                playerView.setPlayer(player);

                for (int i = 0; i < videosArray.length(); i++) {
                    JSONObject v = videosArray.getJSONObject(i);
                    String videoFile = v.getString("url");
                    String grupo = v.getString("grupo");

                    String videoUrl = "http://" + ip_ws + "/api_reprodutor/videos/" + videoFile;
                    MediaItem mediaItem = MediaItem.fromUri(Uri.parse(videoUrl));
                    player.addMediaItem(mediaItem);

                    // Conecta WebSocket apenas no primeiro vídeo
                    if (i == 0) {
                        connectWebSocket(videoUrl, grupo);
                    }
                }

                // Configura loop contínuo
                player.setRepeatMode(Player.REPEAT_MODE_ALL);
                player.prepare();
                player.setPlayWhenReady(true);

                player.addListener(new Player.Listener() {
                    @Override
                    public void onPlaybackStateChanged(int state) {
                        if (state == Player.STATE_READY) {
                            progressBar.setVisibility(View.GONE);
                        }
                    }

                    @Override
                    public void onPlayerError(com.google.android.exoplayer2.PlaybackException error) {
                        Log.e("ExoPlayer", "Erro no player: " + error.getMessage());
                    }
                });

            } catch (Exception e) {
                e.printStackTrace();
                progressBar.setVisibility(View.GONE);
                tvUrl.setText("Erro ao processar JSON");
            }
        }, error -> {
            error.printStackTrace();
            progressBar.setVisibility(View.GONE);
            tvUrl.setText("Erro ao acessar API");
        });

        queue.add(request);
    }

    @Override
    public void onUserInteraction() {
        super.onUserInteraction();

        FloatingActionButton btnSetId = findViewById(R.id.BtnSetDevice);
        if (btnSetId.getVisibility() != View.VISIBLE) {
            btnSetId.setVisibility(View.VISIBLE);

            new Handler().postDelayed(() -> btnSetId.setVisibility(View.GONE), 5000);
        }
    }

    private void restartApp() {
        Intent intent = getIntent();
        finish();
        startActivity(intent);
    }
}