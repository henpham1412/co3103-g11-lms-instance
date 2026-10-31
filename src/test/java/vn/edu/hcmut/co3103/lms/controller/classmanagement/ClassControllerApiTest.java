package vn.edu.hcmut.co3103.lms.controller.classmanagement;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import vn.edu.hcmut.co3103.lms.dto.response.ClassCatalogResponse;
import vn.edu.hcmut.co3103.lms.entity.Class;
import vn.edu.hcmut.co3103.lms.mapper.ClassMapper;
import vn.edu.hcmut.co3103.lms.service.classmanagement.ClassMembershipService;
import vn.edu.hcmut.co3103.lms.service.classmanagement.ClassService;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ClassControllerApiTest {
    private final ClassService classService = mock(ClassService.class);
    private final ClassMembershipService membershipService = mock(ClassMembershipService.class);
    private final ClassMapper classMapper = mock(ClassMapper.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                new ClassController(classService, membershipService, classMapper)
        ).build();
    }

    @Test
    void catalogMapsDocumentedSearchPolicyPaginationAndSortParameters() throws Exception {
        when(classService.browseClasses(eq("Java"), eq(Class.EnrollmentPolicy.OPEN),
                eq(PageRequest.of(1, 5, Sort.by(Sort.Direction.ASC, "title")))))
                .thenReturn(new ClassCatalogResponse(List.of(), 2, 5, 0, 0));

        mockMvc.perform(get("/api/v1/classes")
                        .param("q", "Java")
                        .param("enrollment_policy", "OPEN")
                        .param("page", "2")
                        .param("size", "5")
                        .param("sort", "title,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.size").value(5))
                .andExpect(jsonPath("$.items").isArray());

        verify(classService).browseClasses(eq("Java"), eq(Class.EnrollmentPolicy.OPEN),
                eq(PageRequest.of(1, 5, Sort.by(Sort.Direction.ASC, "title"))));
    }

    @Test
    void catalogRejectsUnsupportedSortField() throws Exception {
        mockMvc.perform(get("/api/v1/classes").param("sort", "tutor_id,asc"))
                .andExpect(status().isBadRequest());
    }
}
