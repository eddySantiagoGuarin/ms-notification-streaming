package com.world_dance.ms_notification_._streaming.service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.world_dance.ms_notification_._streaming.exception.InvalidStreamRequestException;
import com.world_dance.ms_notification_._streaming.exception.KickUnauthorizedException;
import com.world_dance.ms_notification_._streaming.exception.StreamSessionNotFoundException;
import com.world_dance.wd_lib_common.dto.HttpGlobalResponse;
import com.world_dance.wd_lib_common.dto.KickTokenResponseDto;
import com.world_dance.wd_lib_common.dto.StreamPublicResponseDto;
import com.world_dance.wd_lib_common.dto.ToggleStreamStateRequestDto;
import com.world_dance.wd_lib_common.entity.KickOAuthToken;
import com.world_dance.wd_lib_common.entity.StreamSession;
import com.world_dance.wd_lib_common.entity.Timestamps;
import com.world_dance.wd_lib_common.enums.StatusStream;
import com.world_dance.wd_lib_common.repository.StreamSessionRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Servicio de implementación para la integración con la API de Kick y el gestor
 * de transmisiones FFmpeg.
 * Maneja la autenticación OAuth 2.0 con flujo PKCE, el control de ciclo de vida
 * de los streams (Start/Stop)
 * vía comunicación HTTP con ffmpeg-manager, y la actualización de estados en
 * MongoDB y Kick API.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KickApiClientServiceImpl implements KickApiClientService {

    /** Únicas fuentes de video que ffmpeg-manager sabe construir realmente (ver su whitelist). */
    private static final Set<String> VALID_SOURCE_TYPES = Set.of("camera", "screen", "testsrc");

    /**
     * Debe igualar EARLY_FAILURE_WINDOW_MS en ffmpeg-manager/index.js: es la ventana en la que
     * ffmpeg-manager distingue un fallo real de conexión (RTMP/TLS rechazado, credenciales
     * inválidas, red) de un cierre normal. POST /api/stream/start responde 200 en cuanto el proceso
     * de FFmpeg arranca (fire-and-forget), sin esperar a que la conexión hacia Kick se establezca
     * de verdad — sin esta espera, un fallo temprano queda enmascarado: la sesión se marca LIVE y
     * se notifica a Kick que el canal está en vivo aunque nunca haya llegado video real.
     */
    private static final long FFMPEG_START_CONFIRM_TIMEOUT_MS = 8000;
    private static final long FFMPEG_START_CONFIRM_POLL_INTERVAL_MS = 1000;

    private final WebClient.Builder webClientBuilder;
    private final StreamSessionRepository streamSessionRepository;

    @Autowired
    @Lazy
    private StreamSessionService streamSessionService;

    @Value("${kick.api.client-id}")
    private String clientId;

    @Value("${kick.api.client-secret}")
    private String clientSecret;

    @Value("${kick.api.redirect-uri}")
    private String redirectUri;

    @Value("${kick.api.token-url}")
    private String tokenUrl;

    @Value("${kick.api.base-url}")
    private String baseUrl;

    @Value("${ffmpeg.manager.url:http://localhost:3000}")
    private String ffmpegManagerUrl;

    /**
     * Actualiza el estado público del canal en la API de Kick (v1/channels).
     *
     * @param accessToken token Bearer del usuario autenticado en Kick
     * @param enable      booleano indicando el estado deseado para el canal (true =
     *                    LIVE, false = FINISHED)
     * @return true si la solicitud PATCH fue procesada correctamente; false en caso
     *         contrario
     * @throws KickUnauthorizedException si Kick responde 401 (token OAuth expirado/inválido) —
     *         a diferencia de otros fallos (red, Kick caído), este SÍ se propaga porque significa
     *         que la vinculación de la cuenta ya no sirve y el usuario necesita re-vincularla.
     */
    @Override
    public boolean updateStreamStatusOnKick(String accessToken, boolean enable) {
        try {
            WebClient webClient = webClientBuilder.build();

            Map<String, Object> body = new HashMap<>();
            body.put("is_live", enable);

            webClient.patch()
                    .uri("https://api.kick.com/public/v1/channels")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .toBodilessEntity()
                    .block();

            log.info("Estado del canal en Kick actualizado exitosamente a is_live: {}", enable);
            return true;
        } catch (WebClientResponseException.Unauthorized e) {
            log.error("Kick respondió 401 Unauthorized al actualizar is_live={}: el token OAuth expiró o es inválido.", enable);
            throw new KickUnauthorizedException("El token de Kick ha expirado o es inválido. Re-vincula tu cuenta de Kick.");
        } catch (Exception e) {
            log.warn("No se pudo actualizar el estado en Kick via API: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Alterna o establece el estado de transmisión (LIVE / FINISHED) para un
     * evento.
     * Sobrecarga de método sin encabezados de usuario para compatibilidad.
     *
     * @param eventId ID del evento
     * @param request DTO con los parámetros de la solicitud
     * @return respuesta global con los datos públicos de la transmisión
     */
    @Override
    public HttpGlobalResponse<StreamPublicResponseDto> toggleStreamState(Long eventId,
            ToggleStreamStateRequestDto request) {
        return toggleStreamState(eventId, request, null, null);
    }

    /**
     * Alterna o establece el estado de transmisión (LIVE / FINISHED) para un
     * evento.
     * Valida permisos de administración (ownerId del evento, STAFF o ADMIN), invoca
     * a ffmpeg-manager
     * para iniciar o destruir el contenedor Docker de FFmpeg, notifica a Kick API y
     * actualiza MongoDB.
     *
     * @param eventId             ID del evento a controlar
     * @param request             DTO opcional con parámetros como sourceType y
     *                            destinationUrl
     * @param authenticatedUserId ID del usuario autenticado (X-User-Id)
     * @param userRoleHeader      Rol del usuario enviado en encabezados
     *                            (X-User-Role)
     * @return respuesta global con el DTO público de la sesión de transmisión
     */
    @Override
    public HttpGlobalResponse<StreamPublicResponseDto> toggleStreamState(Long eventId,
            ToggleStreamStateRequestDto request, Long authenticatedUserId, String userRoleHeader) {

        // 1. Validar permisos de administración (ownerId, STAFF o ADMIN)
        streamSessionService.validateAdminPermission(eventId, authenticatedUserId, userRoleHeader);

        // 2. Buscar la sesión en base de datos
        StreamSession streamSession = streamSessionRepository.findByEventId(eventId)
                .orElseThrow(
                        () -> new StreamSessionNotFoundException("Sesión de streaming no encontrada para el evento ID: " + eventId));

        // 3. Determinar si debemos activar o desactivar
        boolean isEnable;
        if (request != null && request.getEnable() != null) {
            isEnable = request.getEnable();
        } else {
            // Si no viene la bandera enable, alternamos (toggle) según el estado actual
            isEnable = !StatusStream.LIVE.equals(streamSession.getStatusStream());
        }

        // 4. Obtener token de Kick si existe
        String accessToken = (streamSession.getKickOAuthToken() != null)
                ? streamSession.getKickOAuthToken().getAccessToken()
                : null;

        // 5. Iniciar o detener emisión vía ffmpeg-manager y actualizar estado en BD
        if (isEnable) {
            boolean usingExplicitDestination = request != null && request.getDestinationUrl() != null
                    && !request.getDestinationUrl().isBlank();

            if (!usingExplicitDestination) {
                // El destino real se arma con la stream key que el organizador guardó manualmente
                // (Kick no la expone vía OAuth; se copia a mano desde el panel de Kick al configurar
                // el stream). Si quedó vacía o sin servidor RTMP, FFmpeg arrancaría igual apuntando a
                // una URL sin credenciales válidas y Kick rechazaría la conexión en silencio —visible
                // solo revisando el stderr de ffmpeg-manager— en vez de fallar aquí con un mensaje claro.
                if (streamSession.getStreamKey() == null || streamSession.getStreamKey().isBlank()) {
                    throw new InvalidStreamRequestException(
                            "No se puede iniciar la transmisión: la clave de retransmisión (Stream Key) no está "
                            + "configurada. Configúrala en \"Editar configuración\" antes de iniciar el directo.");
                }
                if (streamSession.getRtmpUrl() == null || streamSession.getRtmpUrl().isBlank()) {
                    throw new InvalidStreamRequestException(
                            "No se puede iniciar la transmisión: el servidor RTMP de destino no está configurado.");
                }
            }

            String destUrl = usingExplicitDestination
                    ? request.getDestinationUrl()
                    : buildDestinationUrl(streamSession.getRtmpUrl(), streamSession.getStreamKey());

            if (destUrl.regionMatches(true, 0, "rtmps://", 0, "rtmps://".length())) {
                log.info("Destino RTMPS (TLS, puerto 443 típico) para el evento {}.", eventId);
            }

            // Sin fallback silencioso a "testsrc": si el frontend no manda una fuente de video real
            // (cámara/pantalla activa), antes esto arrancaba igual con un patrón sintético y Kick
            // terminaba reportando is_live=true sin que llegara ninguna señal real. Ahora se rechaza
            // explícitamente con 400 en vez de "funcionar" en silencio con la fuente equivocada.
            if (request == null || request.getSourceType() == null || request.getSourceType().isBlank()) {
                throw new InvalidStreamRequestException(
                        "No se puede iniciar la transmisión: falta la fuente de video (sourceType). "
                        + "Activa la cámara o la pantalla y espera a que la ingesta confirme la conexión "
                        + "antes de iniciar el directo.");
            }
            String sourceType = request.getSourceType().trim();
            if (!VALID_SOURCE_TYPES.contains(sourceType)) {
                throw new InvalidStreamRequestException(
                        "Fuente de video inválida: '" + sourceType + "'. Debe ser 'camera', 'screen' o 'testsrc'.");
            }

            log.info("Iniciando transmisión para el evento ID: {} -> ffmpeg-manager: sourceType={}, destinationUrl={}",
                    eventId, sourceType, destUrl);

            startFfmpegManager(eventId, destUrl, sourceType);

            // ffmpeg-manager ya aceptó el arranque, pero eso solo significa que el contenedor Docker
            // se lanzó — no que la conexión RTMP/TLS hacia Kick se estableció. Se espera aquí, dentro
            // de la misma ventana de "fallo temprano" que usa ffmpeg-manager, a que confirme que el
            // proceso sigue vivo antes de notificar a Kick y comprometer el estado LIVE.
            confirmFfmpegStreamStarted(eventId);

            // Notificar a Kick ANTES de comprometer el estado a LIVE en BD: si el token OAuth es
            // inválido/expiró (401), no tiene sentido marcar la sesión como en vivo ni dejar el
            // proceso de ffmpeg-manager corriendo hacia un canal que ni siquiera aceptó el cambio de
            // estado — se aborta lo recién iniciado y se propaga el error al frontend.
            if (accessToken != null && !accessToken.isBlank()) {
                try {
                    updateStreamStatusOnKick(accessToken, true);
                } catch (KickUnauthorizedException e) {
                    log.error("Abortando encendido del evento {}: token de Kick inválido/expirado.", eventId);
                    try {
                        stopFfmpegManager(eventId);
                    } catch (Exception stopEx) {
                        // No se reemplaza la causa original (401 de Kick) por este fallo secundario.
                        log.error("Además, no se pudo detener ffmpeg-manager al abortar el evento {}: {}", eventId, stopEx.getMessage());
                    }
                    throw e;
                }
            }

            streamSession.setStatusStream(StatusStream.LIVE);

            if (streamSession.getTimestamps() == null) {
                streamSession.setTimestamps(new Timestamps());
            }
            if (streamSession.getTimestamps().getStartedAt() == null) {
                streamSession.getTimestamps().setStartedAt(Instant.now());
            }

            if (streamSession.getLiveOverlayData() != null) {
                streamSession.getLiveOverlayData().setIsActive(true);
            }
        } else {
            stopFfmpegManager(eventId);

            // A diferencia del encendido, un token de Kick inválido NO debe impedir apagar la
            // transmisión localmente: el usuario siempre debe poder detener su propio stream,
            // vinculación de Kick rota o no. Solo se registra el aviso.
            if (accessToken != null && !accessToken.isBlank()) {
                try {
                    updateStreamStatusOnKick(accessToken, false);
                } catch (KickUnauthorizedException e) {
                    log.warn("No se pudo notificar a Kick el apagado del evento {} (token inválido): {}", eventId, e.getMessage());
                }
            }

            streamSession.setStatusStream(StatusStream.FINISHED);

            if (streamSession.getTimestamps() != null) {
                streamSession.getTimestamps().setEndAt(Instant.now());
            }

            if (streamSession.getLiveOverlayData() != null) {
                streamSession.getLiveOverlayData().setIsActive(false);
            }
        }

        // La notificación a Kick (paso 6) ya se resolvió arriba, dentro de cada rama del if/else:
        // para encender, antes de comprometer el estado LIVE (y abortando ffmpeg-manager si Kick
        // rechaza el token); para apagar, de forma tolerante a fallos.

        // Guardar cambios en la BD
        streamSessionRepository.save(streamSession);

        // 7. Mapear la respuesta DTO
        StreamPublicResponseDto publicResponseDto = new StreamPublicResponseDto();
        publicResponseDto.setId(streamSession.getId());
        publicResponseDto.setEventId(streamSession.getEventId());
        publicResponseDto.setStatusStream(streamSession.getStatusStream());

        if (streamSession.getPlatformConfing() != null) {
            publicResponseDto.setPlayerIframeUrl(streamSession.getPlatformConfing().getPlayerIframeUrl());
            publicResponseDto.setChatIframeUrl(streamSession.getPlatformConfing().getChatIframeUrl());
        }

        publicResponseDto.setLiveOverlayData(streamSession.getLiveOverlayData());
        publicResponseDto.setVodInfo(streamSession.getVodInfo());

        HttpGlobalResponse<StreamPublicResponseDto> response = new HttpGlobalResponse<>();
        response.setData(publicResponseDto);
        response.setMessage("Estado del directo en OBS, Kick y BD actualizado a: " + streamSession.getStatusStream());

        return response;
    }

    /**
     * Pide a ffmpeg-manager iniciar la retransmisión hacia {@code destinationUrl} leyendo la
     * fuente indicada por {@code sourceType}. Extraído como método propio porque también lo
     * necesita el camino de aborto (token de Kick inválido tras un encendido ya iniciado).
     *
     * @throws RuntimeException si ffmpeg-manager no pudo lanzar el proceso
     */
    private void startFfmpegManager(Long eventId, String destUrl, String sourceType) {
        try {
            WebClient webClient = webClientBuilder.build();
            Map<String, Object> ffmpegStartReq = new HashMap<>();
            ffmpegStartReq.put("streamId", eventId.toString());
            ffmpegStartReq.put("destinationUrl", destUrl);
            ffmpegStartReq.put("sourceType", sourceType);

            webClient.post()
                    .uri(ffmpegManagerUrl + "/api/stream/start")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(ffmpegStartReq)
                    .retrieve()
                    .toBodilessEntity()
                    .block();
            log.info("FFmpeg manager iniciado exitosamente para el evento ID: {} con destino: {}", eventId, destUrl);
        } catch (Exception e) {
            log.error("Fallo al iniciar transmisión en ffmpeg-manager ({}/api/stream/start): {}", ffmpegManagerUrl,
                    e.getMessage());
            throw new RuntimeException("No se pudo iniciar la transmisión en ffmpeg-manager: " + e.getMessage());
        }
    }

    /**
     * Espera a que ffmpeg-manager confirme que el proceso de FFmpeg sigue corriendo (no falló) tras
     * el arranque, sondeando su endpoint de estado durante {@code FFMPEG_START_CONFIRM_TIMEOUT_MS}.
     * Si en cualquier momento reporta "FAILED" (conexión RTMP/TLS rechazada, credenciales
     * inválidas, red caída, etc.), aborta con el diagnóstico real capturado del stderr de FFmpeg en
     * vez de dejar que el llamador marque la sesión como en vivo. Si sobrevive toda la ventana sin
     * reportar fallo, se asume que la conexión hacia Kick se estableció correctamente.
     *
     * El contenedor Docker fallido ya se limpia solo (se lanzó con --rm y el proceso ya terminó), así
     * que no hace falta llamar a stopFfmpegManager aquí.
     *
     * @throws InvalidStreamRequestException si ffmpeg-manager reporta el proceso como FAILED
     */
    private void confirmFfmpegStreamStarted(Long eventId) {
        long deadline = System.currentTimeMillis() + FFMPEG_START_CONFIRM_TIMEOUT_MS;

        while (true) {
            Map<String, Object> status = getStreamStatus(eventId);
            String state = status != null ? String.valueOf(status.get("status")) : null;

            if ("FAILED".equals(state)) {
                String errorSummary = status.get("errorSummary") != null
                        ? String.valueOf(status.get("errorSummary"))
                        : "FFmpeg no pudo establecer la conexión con el servidor de destino.";
                throw new InvalidStreamRequestException(
                        "No se pudo iniciar la transmisión hacia Kick: " + errorSummary);
            }

            if (System.currentTimeMillis() >= deadline) {
                return;
            }

            try {
                Thread.sleep(FFMPEG_START_CONFIRM_POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * Pide a ffmpeg-manager detener/destruir el proceso asociado a un evento.
     *
     * @throws RuntimeException si ffmpeg-manager no pudo detener el proceso — el llamador del
     *         apagado normal debe enterarse; quien la use para abortar un encendido fallido debe
     *         atrapar esto por separado para no tapar la excepción original con esta.
     */
    private void stopFfmpegManager(Long eventId) {
        try {
            WebClient webClient = webClientBuilder.build();
            Map<String, Object> ffmpegStopReq = new HashMap<>();
            ffmpegStopReq.put("streamId", eventId.toString());

            webClient.post()
                    .uri(ffmpegManagerUrl + "/api/stream/stop")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(ffmpegStopReq)
                    .retrieve()
                    .toBodilessEntity()
                    .block();
            log.info("FFmpeg manager detenido exitosamente para el evento ID: {}", eventId);
        } catch (Exception e) {
            log.error("Fallo al detener transmisión en ffmpeg-manager ({}/api/stream/stop) para el evento {}: {}",
                    ffmpegManagerUrl, eventId, e.getMessage());
            throw new RuntimeException("No se pudo apagar la transmisión en ffmpeg-manager: " + e.getMessage());
        }
    }

    /**
     * Concatena el servidor RTMP y la stream key en una única URL de destino para FFmpeg, sin
     * importar cómo el usuario haya escrito cada valor (con o sin barra final/inicial). El
     * `rtmpUrl` se guarda en MongoDB exactamente como lo escribió el usuario — sin recortar nada —
     * así que este saneo ocurre únicamente aquí, en el único punto donde de verdad importa evitar
     * una barra doble o ausente ("rtmps://host/app" + "streamKey" -> "rtmps://host/appstreamKey").
     *
     * Kick usa AWS IVS como backend real de ingesta, cuyo endpoint RTMP(S) exige que la stream key
     * viaje bajo el path de aplicación literal "/app" (ej.
     * "rtmps://xxxx.global-contribute.live-video.net:443/app/<streamKey>"). El panel de Kick
     * muestra "Server URL" y "Stream Key" como dos campos separados; si el organizador copia solo
     * el host al guardar la configuración (sin el "/app" final), la URL resultante
     * ("rtmp://host:1935/<streamKey>", sin segmento de aplicación) es rechazada de inmediato por el
     * servidor con "Input/output error" porque nunca llega a la app RTMP correcta. Por eso se
     * inserta "/app" automáticamente cuando no está ya presente, sin duplicarlo si el usuario sí lo
     * incluyó.
     */
    private String buildDestinationUrl(String serverUrl, String streamKey) {
        String cleanUrl = serverUrl.replaceAll("/+$", "");
        String cleanKey = streamKey != null ? streamKey.replaceAll("^/+", "") : "";
        boolean alreadyHasAppPath = cleanUrl.matches("(?i).*/app$");
        String urlWithAppPath = alreadyHasAppPath ? cleanUrl : cleanUrl + "/app";
        return urlWithAppPath + "/" + cleanKey;
    }

    /**
     * Intercambia un código de autorización por tokens de acceso de Kick enviando
     * el verificador PKCE.
     */
    @Override
    public KickOAuthToken exchangeCodeForTokens(String code, String codeVerifier) {
        WebClient webClient = webClientBuilder.build();

        String verifierToSend = (codeVerifier != null && !codeVerifier.isBlank()) ? codeVerifier
                : "0123456789012345678901234567890123456789012";

        KickTokenResponseDto response = webClient.post()
                .uri(tokenUrl)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData("grant_type", "authorization_code")
                        .with("client_id", clientId)
                        .with("client_secret", clientSecret)
                        .with("redirect_uri", redirectUri)
                        .with("code", code)
                        .with("code_verifier", verifierToSend))
                .retrieve()
                .bodyToMono(KickTokenResponseDto.class)
                .block();

        if (response == null || response.getAccessToken() == null) {
            throw new RuntimeException("Error al obtener los tokens de acceso de Kick");
        }

        return KickOAuthToken.builder()
                .accessToken(response.getAccessToken())
                .refreshToken(response.getRefreshToken())
                .expiresIn(response.getExpiresIn())
                .tokenType(response.getTokenType())
                .scope(response.getScope())
                .obtainedAt(Instant.now())
                .build();
    }

    /**
     * Refresca el token de acceso de Kick mediante el grant_type refresh_token.
     */
    @Override
    public KickOAuthToken refreshAccessToken(String refreshToken) {
        WebClient webClient = webClientBuilder.build();

        KickTokenResponseDto response = webClient.post()
                .uri(tokenUrl)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData("grant_type", "refresh_token")
                        .with("client_id", clientId)
                        .with("client_secret", clientSecret)
                        .with("refresh_token", refreshToken))
                .retrieve()
                .bodyToMono(KickTokenResponseDto.class)
                .block();

        if (response == null || response.getAccessToken() == null) {
            throw new RuntimeException("Error al refrescar el token de acceso de Kick");
        }

        return KickOAuthToken.builder()
                .accessToken(response.getAccessToken())
                .refreshToken(response.getRefreshToken() != null ? response.getRefreshToken() : refreshToken)
                .expiresIn(response.getExpiresIn())
                .tokenType(response.getTokenType())
                .scope(response.getScope())
                .obtainedAt(Instant.now())
                .build();
    }

    /**
     * Genera la URL de autorización OAuth 2.0 con PKCE S256 para el inicio de
     * sesión con Kick.
     */
    @Override
    public String generateAuthorizationUrl(String state) {
        try {
            String codeVerifier = "WD_STREAMING_DEVELOPMENT_CODE_VERIFIER_1234567890";

            byte[] bytes = codeVerifier.getBytes(StandardCharsets.US_ASCII);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            String codeChallenge = Base64.getUrlEncoder().withoutPadding().encodeToString(hash);

            return String.format(
                    "%s?response_type=code&client_id=%s&redirect_uri=%s&scope=%s&code_challenge=%s&code_challenge_method=S256&state=%s",
                    "https://id.kick.com/oauth/authorize",
                    clientId,
                    URLEncoder.encode(redirectUri, StandardCharsets.UTF_8),
                    URLEncoder.encode("channel:read channel:write", StandardCharsets.UTF_8),
                    codeChallenge,
                    state);
        } catch (Exception e) {
            throw new RuntimeException("Error al generar la URL de autorización", e);
        }
    }

    /**
     * Obtiene las credenciales de transmisión de Kick asociadas a la cuenta.
     */
    @Override
    public Map<String, String> getStreamCredentials(String accessToken) {
        try {
            WebClient webClient = webClientBuilder.build();

            return webClient.get()
                    .uri("https://api.kick.com/public/v1/channels")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<Map<String, String>>() {
                    })
                    .block();
        } catch (Exception e) {
            throw new RuntimeException("Error al obtener credenciales de OBS desde Kick: " + e.getMessage());
        }
    }

    /**
     * Consulta el estado en tiempo real del proceso en ffmpeg-manager.
     *
     * ffmpeg-manager (Node) es quien realmente ejecuta FFmpeg y captura su stdout/stderr; Java
     * nunca ve esa salida directamente. Cuando el estado reportado es FAILED, ffmpeg-manager ya
     * incluye el diagnóstico y las últimas líneas de stderr en la respuesta — aquí se vuelcan
     * también a los logs de este microservicio en vez de dejarlos solo en la respuesta HTTP, para
     * que un fallo de conexión RTMP hacia Kick quede visible sin tener que ir a buscar los logs de
     * otro contenedor.
     */
    @Override
    public Map<String, Object> getStreamStatus(Long eventId) {
        try {
            WebClient webClient = webClientBuilder.build();
            Map<String, Object> status = webClient.get()
                    .uri(ffmpegManagerUrl + "/api/stream/status/" + eventId)
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {
                    })
                    .block();

            if (status != null && "FAILED".equals(status.get("status"))) {
                log.error(
                        "La transmisión del evento {} falló en ffmpeg-manager (exitCode={}). Causa probable: {}. "
                        + "Últimas líneas de stderr de FFmpeg:\n{}",
                        eventId, status.get("exitCode"), status.get("errorSummary"), status.get("error"));
            }

            return status;
        } catch (Exception e) {
            Map<String, Object> errRes = new HashMap<>();
            errRes.put("streamId", eventId.toString());
            errRes.put("active", false);
            errRes.put("status", "NOT_RUNNING");
            errRes.put("message",
                    "No se encontró transmisión activa o fallo al conectar con ffmpeg-manager: " + e.getMessage());
            return errRes;
        }
    }
}
