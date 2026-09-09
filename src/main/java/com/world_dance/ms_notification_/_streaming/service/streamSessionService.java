package com.world_dance.ms_notification_._streaming.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.world_dance.ms_notification_._streaming.client.EnrollmentFeignClient;
import com.world_dance.ms_notification_._streaming.client.EventCategoryFeignClient;
import com.world_dance.wd_lib_common.dto.CreateStreamSessionRequestDto;
import com.world_dance.wd_lib_common.dto.EventResponseDto;
import com.world_dance.wd_lib_common.dto.FinishStreamRequestDto;
import com.world_dance.wd_lib_common.dto.HttpGlobalResponse;
import com.world_dance.wd_lib_common.dto.LiveStreamResponseDto;
import com.world_dance.wd_lib_common.dto.StreamAdminResponseDto;
import com.world_dance.wd_lib_common.dto.StreamPublicResponseDto;
import com.world_dance.wd_lib_common.dto.UpdateOverlayRequesDto;
import com.world_dance.wd_lib_common.dto.UserEventRoleResponseDto;
import com.world_dance.wd_lib_common.entity.LiveOverlayData;
import com.world_dance.wd_lib_common.entity.PlatformConfing;
import com.world_dance.wd_lib_common.entity.StreamSession;
import com.world_dance.wd_lib_common.entity.Timestamps;
import com.world_dance.wd_lib_common.entity.VodInfo;
import com.world_dance.wd_lib_common.enums.EventRole;
import com.world_dance.wd_lib_common.enums.StatusStream;
import com.world_dance.wd_lib_common.repository.StreamSessionRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Servicio de implementación para la gestión administrativa y persistencia de sesiones de transmisión.
 * Administra el ciclo de vida de los streams en MongoDB, la configuración de URLs de reproducción e ingesta,
 * el overlay en tiempo real y la validación de permisos basados en roles de evento (ownerId, STAFF, ADMIN).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StreamSessionService {

    private final StreamSessionRepository streamSessionRepository;
    private final KickApiClientService kickApiClientService;
    private final EventCategoryFeignClient eventCategoryFeignClient;
    private final EnrollmentFeignClient enrollmentFeignClient;

    /**
     * Valida que el usuario autenticado posea permisos de administración sobre la transmisión del evento.
     * El acceso es concedido únicamente si:
     * 1. El encabezado global X-User-Role es ADMIN u ORGANIZER.
     * 2. El usuario es el creador/organizador del evento (ownerId registrado en ms-event-category).
     * 3. El usuario posee un rol de STAFF o ADMIN asignado en ms-enrollment para el evento.
     *
     * @param eventId             ID del evento a validar
     * @param authenticatedUserId ID del usuario autenticado proveniente del encabezado X-User-Id
     * @param userRoleHeader      Rol del usuario enviado en el encabezado X-User-Role (opcional)
     * @throws SecurityException si el usuario no cuenta con los permisos requeridos
     */
    public void validateAdminPermission(Long eventId, Long authenticatedUserId, String userRoleHeader) {
        if ("ADMIN".equalsIgnoreCase(userRoleHeader) || "ORGANIZER".equalsIgnoreCase(userRoleHeader)) {
            return;
        }

        if (authenticatedUserId == null) {
            throw new SecurityException("Acceso denegado: Se requiere la identificación del usuario autenticado (encabezado X-User-Id).");
        }

        if (eventId != null) {
            // 1. Verificar si es el owner_id (creador del evento en ms-event-category)
            try {
                HttpGlobalResponse<EventResponseDto> eventResponse = eventCategoryFeignClient.getEventById(eventId);
                EventResponseDto event = (eventResponse != null) ? eventResponse.getData() : null;
                if (event != null && event.getOwnerId() != null && event.getOwnerId().equals(authenticatedUserId)) {
                    log.info("Acceso concedido a usuario {} por ser ownerId del evento {}", authenticatedUserId, eventId);
                    return;
                }
            } catch (Exception e) {
                log.warn("No se pudo verificar ownerId en ms-event-category para el evento {}: {}", eventId, e.getMessage());
            }

            // 2. Verificar si posee el rol STAFF o ADMIN en ms-enrollment
            try {
                UserEventRoleResponseDto roleDto = enrollmentFeignClient.getUserEventRole(eventId, authenticatedUserId);
                if (roleDto != null && roleDto.getRoleInEvent() != null) {
                    EventRole role = roleDto.getRoleInEvent();
                    if (role == EventRole.ADMIN || role == EventRole.STAFF) {
                        log.info("Acceso concedido a usuario {} con rol {} en el evento {}", authenticatedUserId, role, eventId);
                        return;
                    }
                }
            } catch (Exception e) {
                log.warn("No se pudo verificar rol en ms-enrollment para el evento {}: {}", eventId, e.getMessage());
            }
        }

        throw new SecurityException("Acceso denegado: Únicamente el creador del evento (owner), STAFF o ADMIN tienen permisos para realizar esta acción.");
    }

    /**
     * Crea y configura una nueva sesión de transmisión en vivo para un evento específico en MongoDB.
     * Valida permisos de administración (ownerId del evento, STAFF o ADMIN).
     *
     * @param request             DTO con la configuración de canal, RTMP y fechas del evento
     * @param authenticatedUserId ID del usuario autenticado
     * @param userRoleHeader      Rol del usuario enviado en encabezados
     * @return respuesta global con los datos administrativos del stream creado
     */
    public HttpGlobalResponse<StreamAdminResponseDto> createStreamSession(CreateStreamSessionRequestDto request, Long authenticatedUserId, String userRoleHeader) {
        if (request == null || request.getEventId() == null) {
            throw new IllegalArgumentException("El ID del evento es obligatorio para crear la sesión de transmisión.");
        }

        // Validar permisos de administración
        validateAdminPermission(request.getEventId(), authenticatedUserId, userRoleHeader);

        if (streamSessionRepository.existsByEventId(request.getEventId())) {
            throw new RuntimeException("Este evento ya tiene una sesión de transmisión creada.");
        }

        StreamSession streamSession = new StreamSession();
        PlatformConfing platformConfig = new PlatformConfing();

        String channelName = request.getChannelUrl().substring(request.getChannelUrl().lastIndexOf("/") + 1);
        platformConfig.setPlayerIframeUrl("https://player.kick.com/" + channelName);
        platformConfig.setChatIframeUrl("https://kick.com/popout/" + channelName + "/chat");

        LiveOverlayData liveOverlayData = new LiveOverlayData();
        liveOverlayData.setIsActive(false);
        liveOverlayData.setRealTimeScore(0.0);

        VodInfo vodInfo = new VodInfo();
        vodInfo.setRecordingUrl("");
        vodInfo.setIsAvailable(false);

        Timestamps timestamp = new Timestamps();
        timestamp.setCreatedAt(Instant.now());
        timestamp.setScheduledFor(request.getScheduleFor());

        StatusStream statusStream = StatusStream.DRAFT;

        String rtmp = (request.getRtmpUrl() != null && !request.getRtmpUrl().isBlank())
                ? request.getRtmpUrl()
                : "rtmps://fa723fc1bfe2.global-contribute.live-video.net/app/";

        streamSession.setEventId(request.getEventId());
        streamSession.setStatusStream(statusStream);
        streamSession.setPlatformConfing(platformConfig);
        streamSession.setLiveOverlayData(liveOverlayData);
        streamSession.setVodInfo(vodInfo);
        streamSession.setTimestamps(timestamp);
        streamSession.setRtmpUrl(rtmp);
        streamSession.setStreamKey(request.getStreamKey());

        streamSessionRepository.save(streamSession);

        StreamAdminResponseDto streamAdminResponseDto = new StreamAdminResponseDto();
        streamAdminResponseDto.setId(streamSession.getId());
        streamAdminResponseDto.setEventId(streamSession.getEventId());
        streamAdminResponseDto.setStatusStream(streamSession.getStatusStream());
        streamAdminResponseDto.setProvider(request.getProvider());
        streamAdminResponseDto.setPlayerIframeUrl(streamSession.getPlatformConfing().getPlayerIframeUrl());
        streamAdminResponseDto.setVodInfo(streamSession.getVodInfo());
        streamAdminResponseDto.setTimestamps(streamSession.getTimestamps());
        streamAdminResponseDto.setLiveOverlayData(streamSession.getLiveOverlayData());
        streamAdminResponseDto.setChannelUrl(request.getChannelUrl());
        streamAdminResponseDto.setChatIframeUrl(streamSession.getPlatformConfing().getChatIframeUrl());
        streamAdminResponseDto.setRtmpUrl(streamSession.getRtmpUrl());
        streamAdminResponseDto.setStreamKey(streamSession.getStreamKey());

        // WebSocket puro (TCP) en vez de WHIP/WebRTC: el UDP de ICE/RTC no atraviesa el túnel de
        // Cloudflare, que solo reenvía HTTP(S)/WS. El navegador graba con MediaRecorder y envía los
        // chunks por este socket hacia ffmpeg-manager, que los reempuja como RTMP hacia SRS.
        String ingestUrl = String.format("wss://api.worlddance.win/ws/ingest/%d", streamSession.getEventId());
        streamAdminResponseDto.setIngestUrl(ingestUrl);

        HttpGlobalResponse<StreamAdminResponseDto> response = new HttpGlobalResponse<>();
        response.setData(streamAdminResponseDto);
        response.setMessage("Sesión de transmisión configurada y creada con éxito.");
        return response;
    }

    /**
     * Actualiza los datos de superposición en vivo (overlay) de la transmisión.
     * Requiere permisos de ownerId, STAFF o ADMIN del evento.
     *
     * @param eventId             ID del evento a actualizar
     * @param request             DTO con datos del participante, puntaje en vivo y slot
     * @param authenticatedUserId ID del usuario autenticado
     * @param userRoleHeader      Rol del usuario enviado en encabezados
     * @return respuesta global con los datos públicos del stream actualizados
     */
    public HttpGlobalResponse<StreamPublicResponseDto> updateOverlay(Long eventId, UpdateOverlayRequesDto request, Long authenticatedUserId, String userRoleHeader) {
        validateAdminPermission(eventId, authenticatedUserId, userRoleHeader);

        StreamSession streamSession = streamSessionRepository.findByEventId(eventId)
                .orElseThrow(() -> new RuntimeException("Sesión de transmisión no encontrada para el evento ID: " + eventId));

        if (streamSession.getLiveOverlayData() == null) {
            streamSession.setLiveOverlayData(new LiveOverlayData());
        }

        streamSession.getLiveOverlayData().setCurrentSlotId(request.getCurrentSlotId());
        streamSession.getLiveOverlayData().setParticipantLabel(request.getParticipantLabel());
        streamSession.getLiveOverlayData().setRealTimeScore(request.getRealTimeScore());
        streamSession.getLiveOverlayData().setIsActive(request.getIsActive());

        streamSessionRepository.save(streamSession);

        StreamPublicResponseDto publicResponseDto = new StreamPublicResponseDto();
        publicResponseDto.setId(streamSession.getId());
        publicResponseDto.setEventId(streamSession.getEventId());
        publicResponseDto.setStatusStream(streamSession.getStatusStream());
        publicResponseDto.setPlayerIframeUrl(streamSession.getPlatformConfing().getPlayerIframeUrl());
        publicResponseDto.setChatIframeUrl(streamSession.getPlatformConfing().getChatIframeUrl());
        publicResponseDto.setLiveOverlayData(streamSession.getLiveOverlayData());
        publicResponseDto.setVodInfo(streamSession.getVodInfo());

        HttpGlobalResponse<StreamPublicResponseDto> response = new HttpGlobalResponse<>();
        response.setData(publicResponseDto);
        response.setMessage("Overlay de la transmisión actualizado con éxito.");
        return response;
    }

    /**
     * Finaliza explícitamente una transmisión por ID de sesión y registra el VOD resultante.
     * Requiere permisos de ownerId, STAFF o ADMIN.
     *
     * @param streamId            ID de la sesión de transmisión en MongoDB
     * @param request             DTO con los datos del VOD y grabación
     * @param authenticatedUserId ID del usuario autenticado
     * @param userRoleHeader      Rol del usuario enviado en encabezados
     * @return respuesta global con la información pública final del stream
     */
    public HttpGlobalResponse<StreamPublicResponseDto> finishStream(String streamId, FinishStreamRequestDto request, Long authenticatedUserId, String userRoleHeader) {
        StreamSession streamSession = streamSessionRepository.findById(streamId)
                .orElseThrow(() -> new RuntimeException("Sesión de transmisión no encontrada para el ID: " + streamId));

        validateAdminPermission(streamSession.getEventId(), authenticatedUserId, userRoleHeader);

        if (streamSession.getTimestamps() == null) {
            streamSession.setTimestamps(new Timestamps());
        }
        if (streamSession.getLiveOverlayData() == null) {
            streamSession.setLiveOverlayData(new LiveOverlayData());
        }
        if (streamSession.getVodInfo() == null) {
            streamSession.setVodInfo(new VodInfo());
        }

        if (streamSession.getKickOAuthToken() != null && streamSession.getKickOAuthToken().getAccessToken() != null) {
            String accessToken = streamSession.getKickOAuthToken().getAccessToken();
            kickApiClientService.updateStreamStatusOnKick(accessToken, false);
        }

        streamSession.setStatusStream(StatusStream.FINISHED);
        streamSession.getTimestamps().setEndAt(Instant.now());
        streamSession.getLiveOverlayData().setIsActive(false);
        streamSession.getVodInfo().setIsAvailable(request.getIsAvailable());
        streamSession.getVodInfo().setRecordingUrl(request.getRecordingUrl());

        streamSessionRepository.save(streamSession);

        StreamPublicResponseDto publicResponseDto = new StreamPublicResponseDto();
        publicResponseDto.setId(streamSession.getId());
        publicResponseDto.setEventId(streamSession.getEventId());
        publicResponseDto.setStatusStream(streamSession.getStatusStream());
        publicResponseDto.setPlayerIframeUrl(streamSession.getPlatformConfing().getPlayerIframeUrl());
        publicResponseDto.setChatIframeUrl(streamSession.getPlatformConfing().getChatIframeUrl());
        publicResponseDto.setLiveOverlayData(streamSession.getLiveOverlayData());
        publicResponseDto.setVodInfo(streamSession.getVodInfo());

        HttpGlobalResponse<StreamPublicResponseDto> response = new HttpGlobalResponse<>();
        response.setData(publicResponseDto);
        response.setMessage("Se finalizó la transmisión con éxito.");
        return response;
    }

    /**
     * Obtiene las credenciales de emisión de OBS asociadas al token OAuth de Kick.
     *
     * @param streamId ID de la sesión de transmisión
     * @return respuesta global con credenciales de la cuenta
     */
    public HttpGlobalResponse<Map<String, String>> getObsCredentials(String streamId) {
        StreamSession streamSession = streamSessionRepository.findById(streamId)
                .orElseThrow(() -> new RuntimeException("Sesión no encontrada: " + streamId));

        String accessToken = streamSession.getKickOAuthToken().getAccessToken();
        Map<String, String> credentials = kickApiClientService.getStreamCredentials(accessToken);

        HttpGlobalResponse<Map<String, String>> response = new HttpGlobalResponse<>();
        response.setData(credentials);
        response.setMessage("Credenciales de OBS obtenidas con éxito.");
        return response;
    }

    /**
     * Consulta pública de los detalles de transmisión de un evento (IFrame, Overlay, Estado).
     *
     * @param eventId ID del evento consultado
     * @return respuesta global con DTO de respuesta pública
     */
    public HttpGlobalResponse<StreamPublicResponseDto> getStreamByEventId(Long eventId) {
        StreamSession streamSession = streamSessionRepository.findByEventId(eventId)
                .orElseThrow(() -> new RuntimeException("No se encontró transmisión configurada para el evento ID: " + eventId));

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
        response.setMessage("Información de la transmisión obtenida con éxito.");
        return response;
    }

    /**
     * Lista pública de todas las sesiones actualmente en vivo (statusStream = LIVE). Alimenta el
     * indicador y el menú "En Vivo" de la navbar del frontend; no requiere autenticación, igual que
     * {@link #getStreamByEventId(Long)}.
     *
     * @return respuesta global con la lista de eventos en vivo (eventId + nombre del evento)
     */
    public HttpGlobalResponse<List<LiveStreamResponseDto>> getLiveStreams() {
        List<LiveStreamResponseDto> liveStreams = streamSessionRepository.findByStatusStream(StatusStream.LIVE)
                .stream()
                .map(this::toLiveStreamResponseDto)
                .filter(dto -> dto != null)
                .toList();

        HttpGlobalResponse<List<LiveStreamResponseDto>> response = new HttpGlobalResponse<>();
        response.setData(liveStreams);
        response.setMessage("Transmisiones en vivo obtenidas con éxito.");
        return response;
    }

    /**
     * Enriquece una sesión en vivo con el nombre real del evento (ms-event-category vía Feign).
     * Si esa consulta falla para un evento puntual, se omite de la lista en vez de tumbar el
     * listado completo por un problema transitorio de un solo evento.
     */
    private LiveStreamResponseDto toLiveStreamResponseDto(StreamSession streamSession) {
        try {
            HttpGlobalResponse<EventResponseDto> eventResponse = eventCategoryFeignClient.getEventById(streamSession.getEventId());
            String eventName = eventResponse.getData() != null ? eventResponse.getData().getName() : null;

            LiveStreamResponseDto dto = new LiveStreamResponseDto();
            dto.setEventId(streamSession.getEventId());
            dto.setEventName(eventName != null ? eventName : ("Evento #" + streamSession.getEventId()));
            return dto;
        } catch (Exception e) {
            log.warn("No se pudo obtener el nombre del evento {} para el listado de transmisiones en vivo: {}",
                    streamSession.getEventId(), e.getMessage());
            return null;
        }
    }

    /**
     * Obtiene la configuración administrativa completa de un stream (incluye credenciales RTMP e Ingesta WHIP).
     * Requiere permisos de ownerId, STAFF o ADMIN del evento.
     *
     * @param eventId             ID del evento
     * @param authenticatedUserId ID del usuario autenticado
     * @param userRoleHeader      Rol del usuario enviado en encabezados
     * @return respuesta global con DTO administrativo de la transmisión
     */
    public HttpGlobalResponse<StreamAdminResponseDto> getAdminStreamByEventId(Long eventId, Long authenticatedUserId, String userRoleHeader) {
        validateAdminPermission(eventId, authenticatedUserId, userRoleHeader);

        StreamSession streamSession = streamSessionRepository.findByEventId(eventId)
                .orElseThrow(() -> new RuntimeException("No se encontró transmisión configurada para el evento ID: " + eventId));

        StreamAdminResponseDto adminResponseDto = new StreamAdminResponseDto();
        adminResponseDto.setId(streamSession.getId());
        adminResponseDto.setEventId(streamSession.getEventId());
        adminResponseDto.setStatusStream(streamSession.getStatusStream());
        adminResponseDto.setVodInfo(streamSession.getVodInfo());
        adminResponseDto.setTimestamps(streamSession.getTimestamps());
        adminResponseDto.setLiveOverlayData(streamSession.getLiveOverlayData());

        if (streamSession.getPlatformConfing() != null) {
            adminResponseDto.setPlayerIframeUrl(streamSession.getPlatformConfing().getPlayerIframeUrl());
            adminResponseDto.setChatIframeUrl(streamSession.getPlatformConfing().getChatIframeUrl());
        }

        adminResponseDto.setRtmpUrl(streamSession.getRtmpUrl());
        adminResponseDto.setStreamKey(streamSession.getStreamKey());

        String ingestUrl = String.format("wss://api.worlddance.win/ws/ingest/%d", streamSession.getEventId());
        adminResponseDto.setIngestUrl(ingestUrl);

        HttpGlobalResponse<StreamAdminResponseDto> response = new HttpGlobalResponse<>();
        response.setData(adminResponseDto);
        response.setMessage("Información administrativa del stream obtenida con éxito.");
        return response;
    }
}
