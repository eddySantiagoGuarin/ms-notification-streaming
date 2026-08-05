package com.world_dance.ms_notification_._streaming.service;

import java.time.Instant;

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


    /**
     * Crea una nueva sesión de transmisión en vivo para un evento específico.
     *
     * @param request DTO que contiene la información necesaria para crear la sesión de transmisión.
     * @return HttpGlobalResponse que contiene los detalles de la sesión de transmisión creada.
     * @throws RuntimeException si ya existe una sesión de transmisión para el evento especificado.
     */
    public HttpGlobalResponse<StreamAdminResponseDto> createStreamSession(CreateStreamSessionRequestDto request){
        
        if(streamSessionRepository.existsByEventId(request.getEventId())){
            throw new RuntimeException("Este evento ya tiene un stream existente");
        }

        StreamSession streamSession = new StreamSession() ;

        PlatformConfing platformConfig = new PlatformConfing() ;

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
        
        StatusStream statusStream = StatusStream.DRAFT ;
        
        streamSession.setEventId(request.getEventId());
        streamSession.setStatusStream(statusStream);
        streamSession.setPlatformConfing(platformConfig);
        streamSession.setLiveOverlayData(liveOverlayData);
        streamSession.setVodInfo(vodInfo);
        streamSession.setTimestamps(timestamp);
        
        streamSessionRepository.save(streamSession);
        

        StreamAdminResponseDto streamAdminResponseDto = new StreamAdminResponseDto() ;

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
        
        HttpGlobalResponse<StreamAdminResponseDto> response = new HttpGlobalResponse<>();

        response.setData(streamAdminResponseDto);
        response.setMessage("Stream configurado y creado con exito.");
        
        return  response ;
    }

    /**
     * Obtiene la información de la sesión de transmisión en vivo para un evento específico.
     *
     * @param eventId ID del evento para el cual se desea obtener la información de la sesión de transmisión.
     * @return HttpGlobalResponse que contiene los detalles de la sesión de transmisión.
     * @throws RuntimeException si no se encuentra una sesión de transmisión configurada para el evento especificado.
     */
    public HttpGlobalResponse<StreamPublicResponseDto> getStreamByEventId(Long eventId) {

        StreamSession streamSession = streamSessionRepository.findByEventId(eventId).orElseThrow(() -> new RuntimeException("No se encontro transmision configurada para el evento ID: " + eventId));

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
        response.setMessage("Información de la transmision obtenida con exito.");

        return response;

    }

    public HttpGlobalResponse<StreamPublicResponseDto> updateOverlay(Long eventId, UpdateOverlayRequesDto request ) {

        StreamSession streamSession = streamSessionRepository.findByEventId(eventId).orElseThrow(() -> new RuntimeException("Session de streaming no encontrada para el evento ID: " + eventId));

        
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
        response.setMessage("Overlay de la transmision actualizada con exito.");

        return response;
 
    }


    public HttpGlobalResponse<StreamPublicResponseDto> finishStream(String streamId, FinishStreamRequestDto request ){

        StreamSession streamSession = streamSessionRepository.findById(streamId).orElseThrow(() -> new RuntimeException("Session de streaming no encontrada para el evento ID: " + streamId));

        if (streamSession.getTimestamps() == null) {
        streamSession.setTimestamps(new Timestamps());
        }
        if (streamSession.getLiveOverlayData() == null) {
            streamSession.setLiveOverlayData(new LiveOverlayData());
        }
        if (streamSession.getVodInfo() == null) {
            streamSession.setVodInfo(new VodInfo());
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
        response.setMessage("Se finalizo la transmision con exito.");

        return response;
    }

    
}
