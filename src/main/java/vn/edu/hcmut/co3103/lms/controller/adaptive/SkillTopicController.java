package vn.edu.hcmut.co3103.lms.controller.adaptive;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.hcmut.co3103.lms.dto.request.adaptive.CreateSkillTopicRequest;
import vn.edu.hcmut.co3103.lms.dto.request.adaptive.UpdateSkillTopicRequest;
import vn.edu.hcmut.co3103.lms.dto.response.adaptive.SkillTopicResponse;
import vn.edu.hcmut.co3103.lms.mapper.adaptive.SkillTopicApiMapper;
import vn.edu.hcmut.co3103.lms.service.adaptive.SkillTopicService;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class SkillTopicController {
    private final SkillTopicService service;

    public SkillTopicController(SkillTopicService service) {
        this.service = service;
    }

    @GetMapping("/classes/{classId}/skill-topics")
    public List<SkillTopicResponse> list(@PathVariable("classId") UUID classId) {
        return service.listByClass(classId).stream().map(SkillTopicApiMapper::toResponse).toList();
    }

    @PostMapping("/classes/{classId}/skill-topics")
    public ResponseEntity<SkillTopicResponse> create(@PathVariable("classId") UUID classId,
                                                    @Valid @RequestBody CreateSkillTopicRequest request) {
        SkillTopicResponse response = SkillTopicApiMapper.toResponse(
                service.create(classId, SkillTopicApiMapper.toCommand(request)));
        return ResponseEntity.created(URI.create("/api/v1/skill-topics/" + response.skillTopicId())).body(response);
    }

    @GetMapping("/skill-topics/{skillTopicId}")
    public SkillTopicResponse get(@PathVariable("skillTopicId") UUID topicId) {
        return SkillTopicApiMapper.toResponse(service.get(topicId));
    }

    @PatchMapping("/skill-topics/{skillTopicId}")
    public SkillTopicResponse update(@PathVariable("skillTopicId") UUID topicId,
                                     @Valid @RequestBody UpdateSkillTopicRequest request) {
        return SkillTopicApiMapper.toResponse(service.update(topicId, SkillTopicApiMapper.toCommand(request)));
    }

    @DeleteMapping("/skill-topics/{skillTopicId}")
    public ResponseEntity<Void> delete(@PathVariable("skillTopicId") UUID topicId) {
        service.delete(topicId);
        return ResponseEntity.noContent().build();
    }
}
