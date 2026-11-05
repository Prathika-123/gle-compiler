package com.gle.controller;

import com.gle.dto.CompileRequest;
import com.gle.dto.CompileResponse;
import com.gle.service.CompilerService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/compiler")
public class CompilerController {
    private final CompilerService service;

    public CompilerController(CompilerService service) { this.service = service; }

    @PostMapping("/compile")
    public ResponseEntity<CompileResponse> compile(@RequestBody CompileRequest request) {
        CompileResponse response = service.compile(request.sourceCode());
        return "REJECTED".equals(response.status()) ? ResponseEntity.badRequest().body(response) : ResponseEntity.ok(response);
    }
}
