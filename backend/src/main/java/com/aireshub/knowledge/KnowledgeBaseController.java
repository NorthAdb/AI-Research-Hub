package com.aireshub.knowledge;

import com.aireshub.common.ApiException;
import com.aireshub.common.AuthUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/knowledge-bases")
public class KnowledgeBaseController {

    public record CreateKbRequest(
            @NotBlank @Size(max = 200) String name,
            @Size(max = 2000) String description) {
    }

    public record KbView(UUID id, String name, String description,
                         Instant createdAt, Instant updatedAt) {

        static KbView from(KnowledgeBase kb) {
            return new KbView(kb.getId(), kb.getName(), kb.getDescription(),
                    kb.getCreatedAt(), kb.getUpdatedAt());
        }
    }

    private final KnowledgeBaseService service;

    public KnowledgeBaseController(KnowledgeBaseService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public KbView create(@Valid @RequestBody CreateKbRequest request, @AuthenticationPrincipal AuthUser user) {
        return service.create(user, request);
    }

    @GetMapping
    public List<KbView> list(@AuthenticationPrincipal AuthUser user) {
        return service.listOwned(user.id());
    }

    @GetMapping("/{id}")
    public KbView get(@PathVariable UUID id, @AuthenticationPrincipal AuthUser user) {
        return KbView.from(service.getOwned(id, user.id()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id, @AuthenticationPrincipal AuthUser user) {
        service.deleteOwned(id, user.id());
    }
}
