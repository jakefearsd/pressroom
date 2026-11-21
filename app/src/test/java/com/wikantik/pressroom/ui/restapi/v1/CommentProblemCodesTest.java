package com.wikantik.pressroom.ui.restapi.v1;

import com.wikantik.pressroom.business.CommentManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.comments.CommentOutcome;
import com.wikantik.pressroom.business.comments.CommentRefusal;
import com.wikantik.pressroom.business.comments.CommentService;
import com.wikantik.pressroom.pojos.ReaderComment;
import com.wikantik.pressroom.pojos.ReaderComment.Status;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.ui.restapi.ApiExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static com.wikantik.pressroom.ui.restapi.v1.ProblemAsserts.oneField;
import static com.wikantik.pressroom.ui.restapi.v1.ProblemAsserts.problem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Every refusal of the comment routes carries its own code, with the English
 * it always had (spec 2026-11-20-api-contract-design, Task 3). The English
 * half passed before Task 3 (characterisation); the code half is what it adds.
 */
class CommentProblemCodesTest {

    private static final String BASE = "/v1/weblogs/myblog/comments";

    private CommentManager comments;
    private CommentService service;
    private Weblog weblog;
    private User owner;
    private ReaderComment comment;
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        Weblogger weblogger = mock(Weblogger.class);
        comments = mock(CommentManager.class);
        service = mock(CommentService.class);
        when(weblogger.getCommentManager()).thenReturn(comments);
        weblog = new Weblog();
        weblog.setId("myblog-id");
        weblog.setHandle("myblog");
        owner = new User();
        owner.setUserName("jake");
        WeblogEntry entry = new WeblogEntry();
        entry.setId("e1");
        entry.setWebsite(weblog);
        comment = new ReaderComment();
        comment.setId("c1");
        comment.setEntry(entry);
        comment.setStatus(Status.PUBLISHED);
        when(comments.getComment("c1")).thenReturn(comment);
        CommentsApi controller = new CommentsApi();
        controller.weblogger = weblogger;
        controller.commentService = service;
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiExceptionHandler()).build();
    }

    private JsonNode answer(ResultMatcher status, MockHttpServletRequestBuilder request) throws Exception {
        String body = mvc.perform(request.requestAttr("actionWeblog", weblog)
                        .requestAttr("authenticatedUser", owner)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status).andReturn().getResponse().getContentAsString();
        return new ObjectMapper().readTree(body);
    }

    @Test
    void anUnknownCommentIsCommentNotFound() throws Exception {
        problem(answer(status().isNotFound(), get(BASE + "/nope")), "comment.notFound", "No such comment.");
    }

    @Test
    void theStatusRefusalsHaveTheirCodes() throws Exception {
        oneField(answer(status().isBadRequest(), patch(BASE + "/c1").content("{\"status\":\"PENDING\"}")),
                "status", "comment.statusNotSettable", "status must be PUBLISHED or HIDDEN.");
        oneField(answer(status().isBadRequest(), patch(BASE + "/c1").content("{\"status\":\"DELETED\"}")),
                "status", "comment.statusUnknown", "status must be one of PENDING, PUBLISHED or HIDDEN.");
    }

    @Test
    void publishingATombstoneIsCommentDeletedByAuthor() throws Exception {
        when(service.changeStatus(any(), any(), any())).thenReturn(false);
        problem(answer(status().isConflict(), patch(BASE + "/c1").content("{\"status\":\"PUBLISHED\"}")),
                "comment.deletedByAuthor", "Its author deleted this comment; it cannot be published.");
    }

    /** {0} is the refusal's constant name: a token the client can branch on, not prose. */
    @Test
    void aRefusedReplyIsCommentReplyRefused() throws Exception {
        when(service.reply(owner, comment, "x")).thenReturn(CommentOutcome.refused(CommentRefusal.BAD_REPLY));
        problem(answer(status().isBadRequest(), post(BASE + "/c1/replies").content("{\"body\":\"x\"}")),
                "comment.replyRefused", "The reply was refused: BAD_REPLY");
    }
}
