package com.world_dance.ms_notification_._streaming.controller;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.world_dance.ms_notification_._streaming.service.KickApiClientService;
import com.world_dance.ms_notification_._streaming.service.StreamSessionService;
import com.world_dance.wd_lib_common.dto.CreateStreamSessionRequestDto;
import com.world_dance.wd_lib_common.dto.FinishStreamRequestDto;
import com.world_dance.wd_lib_common.dto.HttpGlobalResponse;
import com.world_dance.wd_lib_common.dto.StreamAdminResponseDto;
import com.world_dance.wd_lib_common.dto.StreamPublicResponseDto;
import com.world_dance.wd_lib_common.dto.ToggleStreamStateRequestDto;
import com.world_dance.wd_lib_common.dto.UpdateOverlayRequesDto;
import com.world_dance.wd_lib_common.entity.KickOAuthToken;
import com.world_dance.wd_lib_common.entity.StreamSession;
import com.world_dance.wd_lib_common.repository.StreamSessionRepository;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;



@RestController
@RequiredArgsConstructor
@RequestMapping("/stream")
public class StreamSessionController {
    
    private final StreamSessionService streamSessionService ;

    private final KickApiClientService kickApiClientService ;

    private final StreamSessionRepository streamSessionRepository;


    /**
     * Endpoint para crear una nueva sesión de transmisión en vivo para un evento específico.
     *
     * @param request DTO que contiene la información necesaria para crear la sesión de transmisión.
     * @return ResponseEntity que contiene el HttpGlobalResponse con los detalles de la sesión de transmisión creada.
     *         Si ocurre un error, devuelve un ResponseEntity con el mensaje de error y el estado BAD_REQUEST.
     */
    @PostMapping("/createStreamSession")
    public ResponseEntity<HttpGlobalResponse<StreamAdminResponseDto>> createStreaminSeassion(@Valid @RequestBody CreateStreamSessionRequestDto request) {
        try {

            HttpGlobalResponse<StreamAdminResponseDto> response = streamSessionService.createStreamSession(request);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
           
        } catch (Exception e) {
            HttpGlobalResponse<StreamAdminResponseDto> response = new HttpGlobalResponse<>();
            response.setMessage(e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
        } 
    }
    
    /**
     * Endpoint para obtener la información de la sesión de transmisión en vivo para un evento específico.
     *
     * @param eventId ID del evento para el cual se desea obtener la información de la sesión de transmisión.
     * @return ResponseEntity que contiene el HttpGlobalResponse con los detalles de la sesión de transmisión.
     *         Si ocurre un error, devuelve un ResponseEntity con el mensaje de error y el estado NOT_FOUND.
     */
    @GetMapping("/event/{eventId}")
    public ResponseEntity<HttpGlobalResponse<StreamPublicResponseDto>> getStreamByEventId(@PathVariable Long eventId) {
        try {
            HttpGlobalResponse<StreamPublicResponseDto> response = streamSessionService.getStreamByEventId(eventId);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            HttpGlobalResponse<StreamPublicResponseDto> response = new HttpGlobalResponse<>();
            response.setMessage(e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
        }
    }

    @PutMapping("/updateOverlay/{eventId}")
    public ResponseEntity<HttpGlobalResponse<StreamPublicResponseDto>> updateOverlay(@PathVariable Long eventId,@Valid @RequestBody UpdateOverlayRequesDto request) {
        try {
            HttpGlobalResponse<StreamPublicResponseDto> response = streamSessionService.updateOverlay(eventId, request);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            HttpGlobalResponse<StreamPublicResponseDto> response = new HttpGlobalResponse<>();
            response.setMessage(e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
        }
    }

    @PatchMapping("/finish/{streamId}")
    public ResponseEntity<HttpGlobalResponse<StreamPublicResponseDto>> finishStream(@PathVariable String streamId, @RequestBody  FinishStreamRequestDto request ) {
        try {
            HttpGlobalResponse<StreamPublicResponseDto> response = streamSessionService.finishStream(streamId, request);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            HttpGlobalResponse<StreamPublicResponseDto> response = new HttpGlobalResponse<>();
            response.setMessage(e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
        }
    }

    @RequestMapping(value = "/toggleState/{eventId}", method = {org.springframework.web.bind.annotation.RequestMethod.PATCH, org.springframework.web.bind.annotation.RequestMethod.POST, org.springframework.web.bind.annotation.RequestMethod.GET})
    public ResponseEntity<HttpGlobalResponse<StreamPublicResponseDto>> toggleStreamState(
            @PathVariable Long eventId,
            @RequestBody(required = false) ToggleStreamStateRequestDto request,
            @RequestParam(required = false) Boolean enable) {
        try {
            if (request == null && enable != null) {
                request = new ToggleStreamStateRequestDto(enable);
            }
            HttpGlobalResponse<StreamPublicResponseDto> response = kickApiClientService.toggleStreamState(eventId, request);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            HttpGlobalResponse<StreamPublicResponseDto> response = new HttpGlobalResponse<>();
            response.setMessage(e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
        }
    }

    @GetMapping("/oauth/callback")
    public ResponseEntity<HttpGlobalResponse<String>> handleKickCallback(
        @RequestParam("code") String code,
        @RequestParam(value = "state", required = false) String state) {

    // Verifier fijo que usamos en el login
    String codeVerifier = "WD_STREAMING_DEVELOPMENT_CODE_VERIFIER_1234567890";

    // 1. Intercambiar el código enviando el verifier
    KickOAuthToken tokens = kickApiClientService.exchangeCodeForTokens(code, codeVerifier);

    // 2. Asociar tokens a la sesión (usando 'state' como eventId si viene presente)
    if (state != null) {
        Long eventId = Long.parseLong(state);
        StreamSession session = streamSessionRepository.findByEventId(eventId)
                .orElseThrow(() -> new RuntimeException("No se encontró sesión para el evento: " + eventId));
        
        session.setKickOAuthToken(tokens);
        streamSessionRepository.save(session);
    }

    HttpGlobalResponse<String> response = new HttpGlobalResponse<>();
    response.setData(tokens.getAccessToken());
    response.setMessage("¡Autenticación con Kick exitosa! Token guardado en MongoDB.");

    return ResponseEntity.ok(response);
}


    @GetMapping("/oauth/kick/login")
    public ResponseEntity<HttpGlobalResponse<String>> getKickAuthUrl(@RequestParam(defaultValue = "1") String state) {
        String authUrl = kickApiClientService.generateAuthorizationUrl(state);
        
        HttpGlobalResponse<String> response = new HttpGlobalResponse<>();
        response.setData(authUrl);
        response.setMessage("Abre el enlace adjunto en el navegador para autorizar Kick.");
        
        return ResponseEntity.ok(response);
    }

    @GetMapping("/credentials/{streamId}")
    public ResponseEntity<HttpGlobalResponse<Map<String, String>>> getObsCredentials(@PathVariable String streamId) {
        HttpGlobalResponse<Map<String, String>> response = streamSessionService.getObsCredentials(streamId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/admin/event/{eventId}")
    public ResponseEntity<HttpGlobalResponse<StreamAdminResponseDto>> getAdminStreamByEventId(@PathVariable Long eventId){
        try {
            HttpGlobalResponse<StreamAdminResponseDto> response = streamSessionService.getAdminStreamByEventId(eventId);
            response.setMessage("Información de la sesión de transmisión obtenida correctamente.");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            HttpGlobalResponse<StreamAdminResponseDto> response = new HttpGlobalResponse<>();
            response.setMessage(e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
        }
    }
}
