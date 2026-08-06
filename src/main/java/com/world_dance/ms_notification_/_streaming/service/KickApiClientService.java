package com.world_dance.ms_notification_._streaming.service;

import java.util.Map;

import com.world_dance.wd_lib_common.dto.HttpGlobalResponse;
import com.world_dance.wd_lib_common.dto.StreamPublicResponseDto;
import com.world_dance.wd_lib_common.dto.ToggleStreamStateRequestDto;
import com.world_dance.wd_lib_common.entity.KickOAuthToken;

public interface KickApiClientService {

   KickOAuthToken exchangeCodeForTokens(String code, String codeVerifier);

    KickOAuthToken refreshAccessToken(String refreshToken);

    boolean updateStreamStatusOnKick(String accessToken, boolean enable);

    String generateAuthorizationUrl(String state);

    HttpGlobalResponse<StreamPublicResponseDto> toggleStreamState(Long eventId, ToggleStreamStateRequestDto request);

    Map<String, String> getStreamCredentials(String accessToken);

}
