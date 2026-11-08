package vn.edu.hcmut.co3103.lms.controller.adaptive;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.hcmut.co3103.lms.dto.request.adaptive.ReplacePrerequisitesRequest;
import vn.edu.hcmut.co3103.lms.dto.response.adaptive.SkillPrerequisiteResponse;
import vn.edu.hcmut.co3103.lms.mapper.adaptive.SkillTopicApiMapper;
import vn.edu.hcmut.co3103.lms.service.adaptive.SkillPrerequisiteService;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/skill-topics/{skillTopicId}/prerequisites")
public class SkillPrerequisiteController {
    private final SkillPrerequisiteService service;

    public SkillPrerequisiteController(SkillPrerequisiteService service) {
        this.service = service;
    }

    @GetMapping
    public List<SkillPrerequisiteResponse> list(@PathVariable("skillTopicId") UUID topicId) {
        return service.list(topicId).stream().map(SkillTopicApiMapper::toResponse).toList();
    }

    @PutMapping
    public ResponseEntity<Void> replace(@PathVariable("skillTopicId") UUID topicId,
                                        @Valid @RequestBody ReplacePrerequisitesRequest request) {
        service.replace(topicId, request.prerequisiteSkillTopicIds());
        return ResponseEntity.noContent().build();
    }
}
