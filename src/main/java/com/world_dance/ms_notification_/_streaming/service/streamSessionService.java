package com.world_dance.ms_notification_._streaming.service;

import org.springframework.stereotype.Service;

import com.world_dance.wd_lib_common.repository.StreamSessionRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class streamSessionService {

    private final StreamSessionRepository streamSessionRepository ;


   /*  public HttpGlobalResponse<StreamAdminResponseDto> createStreamSession(CreateStreamSessionRequestDto request){
        
        if(streamSessionRepository.existsByEventId(request.getEventId())){
            throw new RuntimeException("Este evento ya tiene un stream existente");
        }

        StreamSession streamSession = new StreamSession() ;

        PlatformConfing platformConfig = new PlatformConfing() ;

        platformConfig.setChannelUrl(request.getChannelUrl());
        platformConfig.setChannelUrl(request.getChannelUrl()+"/chat");

        streamSession.setPlatformConfing(platformConfig);

        LiveOverlayData liveOverlayData = new LiveOverlayData();

        liveOverlayData.setIsActive(false);
        liveOverlayData.setRealTimeScore(0.0);

        VodInfo vodInfo = new VodInfo();
        
        vodInfo.setRecordingUrl("");
        vodInfo.setIsAvailable(false);

        Timestamps timestamp = new Timestamps();


        timestamp.setCreatedAt(Instant.now());
        timestamp.setScheduledFor(request.getScheduleFor());

        streamSession.setEventId(request.getEventId());

        StatusStream statusStream = StatusStream.DRAFT ;

        streamSession.setStatusStream(statusStream);
        
        streamSessionRepository.save(streamSession);

        StreamAdminResponseDto streamAdminResponseDto = new StreamAdminResponseDto() ;

        streamAdminResponseDto.setId(streamSession.getId());
        streamAdminResponseDto.set
        streamAdminResponseDto
        streamAdminResponseDto
        streamAdminResponseDto
        
    }*/


}
