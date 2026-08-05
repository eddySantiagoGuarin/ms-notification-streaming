package com.world_dance.ms_notification_._streaming.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.world_dance.ms_notification_._streaming.service.StreamSessionService;
import com.world_dance.wd_lib_common.dto.CreateStreamSessionRequestDto;
import com.world_dance.wd_lib_common.dto.HttpGlobalResponse;
import com.world_dance.wd_lib_common.dto.StreamAdminResponseDto;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;



@RestController
@RequiredArgsConstructor
@RequestMapping("/stream")
public class StreamSessionController {
    
    private final StreamSessionService streamSessionService ;


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
    

}
