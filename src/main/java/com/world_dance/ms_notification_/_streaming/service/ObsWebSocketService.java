package com.world_dance.ms_notification_._streaming.service;

import java.net.URI;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class ObsWebSocketService {

    @Value("${obs.websocket.host:localhost}")
    private String host;

    @Value("${obs.websocket.port:4455}")
    private int port;

    public void startStreaming() {
        executeObsCommand("StartStream", null);
    }

    public void stopStreaming() {
        executeObsCommand("StopStream", null);
    }

    public void setStreamSettingsAndStart(String rtmpUrl, String streamKey) {
        // 1. Aplicamos la configuración del servidor RTMP y StreamKey
        String requestDataJson = String.format("""
            "streamServiceType": "rtmp_custom",
            "streamServiceSettings": {
              "server": "%s",
              "key": "%s"
            }
            """, rtmpUrl, streamKey);

        executeObsCommand("SetStreamServiceSettings", requestDataJson);

        try {
            Thread.sleep(400); // Pequeña pausa para que OBS guarde las credenciales
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // 2. Iniciamos el Stream
        startStreaming();
    }

    private void executeObsCommand(String requestType, String customDataJson) {
        String wsUrl = String.format("ws://%s:%d", host, port);

        try {
            StandardWebSocketClient client = new StandardWebSocketClient();
            client.execute(new TextWebSocketHandler() {
                @Override
                public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                    // STEP 1: Handshake de Identificación obligatorio de OBS WebSocket v5 (Opcode 1)
                    String identifyPayload = """
                        {
                          "op": 1,
                          "d": {
                            "rpcVersion": 1
                          }
                        }
                        """;
                    session.sendMessage(new TextMessage(identifyPayload));
                    
                    Thread.sleep(150); // Esperar que OBS procese la identificación

                    // STEP 2: Enviar el Request deseado (Opcode 6)
                    String dataSection = (customDataJson != null && !customDataJson.isBlank()) 
                            ? ",\"requestData\": {" + customDataJson + "}" 
                            : "";

                    String requestPayload = String.format("""
                        {
                          "op": 6,
                          "d": {
                            "requestType": "%s",
                            "requestId": "req-%d"%s
                          }
                        }
                        """, requestType, System.currentTimeMillis(), dataSection);

                    session.sendMessage(new TextMessage(requestPayload));
                    log.info("Comando WebSocket enviado exitosamente a OBS: {}", requestType);

                    Thread.sleep(300);
                    session.close();
                }
            }, null, URI.create(wsUrl)).get();

        } catch (Exception e) {
            log.error("Error al comunicar con OBS via WebSocket: {}", e.getMessage());
        }
    }
}