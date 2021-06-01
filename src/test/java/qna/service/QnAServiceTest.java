package qna.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import qna.CannotDeleteException;
import qna.NotFoundException;
import qna.domain.Answer;
import qna.domain.AnswerRepository;
import qna.domain.ContentType;
import qna.domain.DateTimeStrategy;
import qna.domain.DeleteHistories;
import qna.domain.DeleteHistory;
import qna.domain.Question;
import qna.domain.QuestionRepository;
import qna.domain.User;
import qna.domain.UserRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("NonAsciiCharacters")
class QnAServiceTest {

    @Mock
    private QuestionRepository questionRepository;

    @Mock
    private AnswerRepository answerRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private DeleteHistoryService deleteHistoryService;

    @Spy
    private DateTimeStrategy dateTimeStrategy;

    @InjectMocks
    private QnAService qnAService;

    private User loginUser;
    private User otherUser;

    private Question question;
    private Answer answer;

    @BeforeEach
    public void setUp() {

        dateTimeStrategy = () -> LocalDateTime.of(2021, 6, 1, 0, 0, 0);

        loginUser = new User(1L, "loginUser", "pwd", "name1", "email");
        otherUser = new User(2L, "loginUser", "pwd", "name1", "email");

        question = new Question(1L, "title1", "contents1").writeBy(loginUser);
        answer = new Answer(1L, loginUser, question, "Answers Contents1");

        question.addAnswer(answer);
    }

    @Test
    public void delete_성공() throws Exception {

        when(questionRepository.findById(eq(question.getId())))
            .thenReturn(Optional.of(question));

        when(userRepository.findById(eq(loginUser.getId())))
            .thenReturn(Optional.of(loginUser));

        assertThat(question.isDeleted()).isFalse();
        qnAService.deleteQuestion(loginUser.getId(), question.getId());

        assertThat(question.isDeleted()).isTrue();
        verifyDeleteHistories();
    }

    @Test
    public void delete_찾을_수_없는_질문() {

        long notFoundQuestionId = 9999L;

        when(questionRepository.findById(eq(notFoundQuestionId)))
            .thenReturn(Optional.empty());

        assertThatThrownBy(() -> qnAService.deleteQuestion(loginUser.getId(), notFoundQuestionId))
            .isInstanceOf(CannotDeleteException.class)
            .hasMessage(QnAService.MESSAGE_QUESTION_NOT_FOUND);
    }

    @Test
    public void delete_잘못된_사용자() {

        long notFoundUserId = 9999L;

        when(questionRepository.findById(eq(question.getId())))
            .thenReturn(Optional.of(question));

        when(userRepository.findById(eq(notFoundUserId)))
            .thenReturn(Optional.empty());

        assertThatThrownBy(() -> qnAService.deleteQuestion(notFoundUserId, question.getId()))
            .isInstanceOf(NotFoundException.class)
            .hasMessage(QnAService.MESSAGE_USER_NOT_FOUND);
    }

    @Test
    public void delete_이미_삭제된_질문() throws CannotDeleteException {

        Question deletedQuestion = new Question(2L, "title", "contents").writeBy(loginUser);
        deletedQuestion.delete(loginUser, dateTimeStrategy.now());

        when(questionRepository.findById(eq(deletedQuestion.getId())))
            .thenReturn(Optional.of(deletedQuestion));

        when(userRepository.findById(eq(loginUser.getId())))
            .thenReturn(Optional.of(loginUser));

        assertThatThrownBy(() -> qnAService.deleteQuestion(loginUser.getId(), deletedQuestion.getId()))
            .isInstanceOf(CannotDeleteException.class)
            .hasMessage(Question.MESSAGE_ALREADY_DELETED);
    }

    @Test
    public void delete_다른_사람이_쓴_글() {

        when(questionRepository.findById(eq(question.getId())))
            .thenReturn(Optional.of(question));

        when(userRepository.findById(eq(otherUser.getId())))
            .thenReturn(Optional.of(otherUser));

        assertThatThrownBy(() -> qnAService.deleteQuestion(otherUser.getId(), question.getId()))
                .isInstanceOf(CannotDeleteException.class)
                .hasMessage(Question.MESSAGE_HAS_NOT_DELETE_PERMISSION);
    }

    @Test
    public void delete_성공_질문자_답변자_모두_같음() throws Exception {

        Answer answer2 = new Answer(2L, loginUser, question, "Answers Contents1");
        question.addAnswer(answer2);

        when(questionRepository.findById(eq(question.getId())))
            .thenReturn(Optional.of(question));

        when(userRepository.findById(eq(loginUser.getId())))
            .thenReturn(Optional.of(loginUser));

        qnAService.deleteQuestion(loginUser.getId(), question.getId());

        assertThat(question.isDeleted()).isTrue();
        assertThat(answer.isDeleted()).isTrue();
        verifyDeleteHistories(answer2);
    }

    @Test
    public void delete_답변_중_다른_사람이_쓴_글() {

        Answer answer2 = new Answer(2L, otherUser, question, "Answers Contents1");
        question.addAnswer(answer2);

        when(questionRepository.findById(eq(question.getId())))
            .thenReturn(Optional.of(question));

        when(userRepository.findById(eq(loginUser.getId())))
            .thenReturn(Optional.of(loginUser));

        assertThatThrownBy(() -> qnAService.deleteQuestion(loginUser.getId(), question.getId()))
                .isInstanceOf(CannotDeleteException.class)
                .hasMessage(Question.MESSAGE_HAS_OTHER_USER_ANSWER);
    }

    private void verifyDeleteHistories(Answer... additionalAnswers) {

        DeleteHistories deleteHistories =
            new DeleteHistories(
                new DeleteHistory(ContentType.QUESTION, question.getId(), question.getWriter(), LocalDateTime.now()),
                new DeleteHistory(ContentType.ANSWER, answer.getId(), answer.getWriter(), LocalDateTime.now()));

        if (additionalAnswers != null) {

            List<DeleteHistory> deleted = new ArrayList<>();

            for (Answer additionalAnswer : additionalAnswers) {
                deleted.add(
                    new DeleteHistory(ContentType.ANSWER, additionalAnswer.getId(), additionalAnswer.getWriter(), LocalDateTime.now())
                );
            }

            if (deleted.size() > 0) {
                deleteHistories = deleteHistories.addAll(new DeleteHistories(deleted));
            }
        }

        verify(deleteHistoryService).saveAll(deleteHistories);
    }
}
