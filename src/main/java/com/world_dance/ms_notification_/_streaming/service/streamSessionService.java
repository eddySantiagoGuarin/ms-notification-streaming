package com.world_dance.ms_notification_._streaming.service;

import java.time.Instant;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.world_dance.wd_lib_common.dto.CreateStreamSessionRequestDto;
import com.world_dance.wd_lib_common.dto.FinishStreamRequestDto;
import com.world_dance.wd_lib_common.dto.HttpGlobalResponse;
import com.world_dance.wd_lib_common.dto.StreamAdminResponseDto;
import com.world_dance.wd_lib_common.dto.StreamPublicResponseDto;
import com.world_dance.wd_lib_common.dto.UpdateOverlayRequesDto;
import com.world_dance.wd_lib_common.entity.LiveOverlayData;
import com.world_dance.wd_lib_common.entity.PlatformConfing;
import com.world_dance.wd_lib_common.entity.StreamSession;
import com.world_dance.wd_lib_common.entity.Timestamps;
import com.world_dance.wd_lib_common.entity.VodInfo;
import com.world_dance.wd_lib_common.enums.StatusStream;
import com.world_dance.wd_lib_common.repository.StreamSessionRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class StreamSessionService {

    private final StreamSessionRepository streamSessionRepository ;

    private final KickApiClientService kickApiClientService ;


    /**
     * Crea una nueva sesión de transmisión en vivo para un evento específico.
     *
     * @param request DTO que contiene la información necesaria para crear la sesión de transmisión.
     * @return HttpGlobalResponse que contiene los detalles de la sesión de transmisión creada.
     * @throws RuntimeException si ya existe una sesión de transmisión para el evento especificado.
     */
    public HttpGlobalResponse<StreamAdminResponseDto> createStreamSession(CreateStreamSessionRequestDto request) {
        
        if (streamSessionRepository.existsByEventId(request.getEventId())) {
            throw new RuntimeException("Este evento ya tiene un stream existente");
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
        
        HttpGlobalResponse<StreamAdminResponseDto> response = new HttpGlobalResponse<>();
        response.setData(streamAdminResponseDto);
        response.setMessage("Stream configurado y creado con exito.");
        
        return response;
    }

    public HttpGlobalResponse<StreamPublicResponseDto> updateOverlay(Long eventId, UpdateOverlayRequesDto request) {

        StreamSession streamSession = streamSessionRepository.findByEventId(eventId)
                .orElseThrow(() -> new RuntimeException("Session de streaming no encontrada para el evento ID: " + eventId));

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
        publicResponseDto.setEventId(streamSession.getId());
        publicResponseDto.setStatusStream(streamSession.getStatusStream());
        publicResponseDto.setPlayerIframeUrl(streamSession.getPlatformConfing().getPlayerIframeUrl());
        publicResponseDto.setChatIframeUrl(streamSession.getPlatformConfing().getChatIframeUrl());
        publicResponseDto.setLiveOverlayData(streamSession.getLiveOverlayData());
        publicResponseDto.setVodInfo(streamSession.getVodInfo());

        HttpGlobalResponse<StreamPublicResponseDto> response = new HttpGlobalResponse<>();
        response.setData(publicResponseDto);
        response.setMessage("Overlay de la transmision actualizada con exito.");

        return response;
    }


    public HttpGlobalResponse<StreamPublicResponseDto> finishStream(String streamId, FinishStreamRequestDto request) {

        StreamSession streamSession = streamSessionRepository.findById(streamId)
                .orElseThrow(() -> new RuntimeException("Session de streaming no encontrada para el evento ID: " + streamId));

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
        publicResponseDto.setEventId(streamSession.getId());
        publicResponseDto.setStatusStream(streamSession.getStatusStream());
        publicResponseDto.setPlayerIframeUrl(streamSession.getPlatformConfing().getPlayerIframeUrl());
        publicResponseDto.setChatIframeUrl(streamSession.getPlatformConfing().getChatIframeUrl());
        publicResponseDto.setLiveOverlayData(streamSession.getLiveOverlayData());
        publicResponseDto.setVodInfo(streamSession.getVodInfo());

        HttpGlobalResponse<StreamPublicResponseDto> response = new HttpGlobalResponse<>();
        response.setData(publicResponseDto);
        response.setMessage("Se finalizo la transmision con exito.");

        return response;
    }


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
     * Obtiene la configuración administrativa completa de un stream (incluye credenciales de OBS) por ID de evento.
     *
     * @param eventId ID del evento.
     * @return HttpGlobalResponse con StreamAdminResponseDto.
     */
    public HttpGlobalResponse<StreamPublicResponseDto> getStreamByEventId(Long eventId) {

        StreamSession streamSession = streamSessionRepository.findByEventId(eventId)
                .orElseThrow(() -> new RuntimeException("No se encontro transmision configurada para el evento ID: " + eventId));

        StreamPublicResponseDto publicResponseDto = new StreamPublicResponseDto();

        publicResponseDto.setId(streamSession.getId());
        publicResponseDto.setEventId(streamSession.getId());
        publicResponseDto.setStatusStream(streamSession.getStatusStream());
        
        if (streamSession.getPlatformConfing() != null) {
            publicResponseDto.setPlayerIframeUrl(streamSession.getPlatformConfing().getPlayerIframeUrl());
            publicResponseDto.setChatIframeUrl(streamSession.getPlatformConfing().getChatIframeUrl());
        }

        publicResponseDto.setLiveOverlayData(streamSession.getLiveOverlayData());
        publicResponseDto.setVodInfo(streamSession.getVodInfo());

        HttpGlobalResponse<StreamPublicResponseDto> response = new HttpGlobalResponse<>();
        response.setData(publicResponseDto);
        response.setMessage("Información de la transmision obtenida con exito.");

        return response;
    }

    public HttpGlobalResponse<StreamAdminResponseDto> getAdminStreamByEventId(Long eventId) {

        StreamSession streamSession = streamSessionRepository.findByEventId(eventId)
                .orElseThrow(() -> new RuntimeException("No se encontro transmision configurada para el evento ID: " + eventId));

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

        HttpGlobalResponse<StreamAdminResponseDto> response = new HttpGlobalResponse<>();
        response.setData(adminResponseDto);
        response.setMessage("Información administrativa del stream obtenida con éxito.");

        return response;
    }
    
}
