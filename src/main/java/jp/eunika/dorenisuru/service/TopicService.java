package jp.eunika.dorenisuru.service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.persistence.EntityNotFoundException;
import javax.transaction.Transactional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import jp.eunika.dorenisuru.common.util.AppProperties;
import jp.eunika.dorenisuru.common.util.BeanUtil;
import jp.eunika.dorenisuru.common.util.Tuple2;
import jp.eunika.dorenisuru.domain.entity.Choice;
import jp.eunika.dorenisuru.domain.entity.Topic;
import jp.eunika.dorenisuru.domain.entity.Voter;
import jp.eunika.dorenisuru.domain.entity.VoterChoice;
import jp.eunika.dorenisuru.domain.entity.VoterChoice.Feeling;
import jp.eunika.dorenisuru.domain.misc.VoteSummary;
import jp.eunika.dorenisuru.domain.repository.ChoiceRepository;
import jp.eunika.dorenisuru.domain.repository.TopicRepository;
import jp.eunika.dorenisuru.domain.repository.VoterRepository;
import jp.eunika.dorenisuru.web.form.TopicForm;
import jp.eunika.dorenisuru.web.form.VoteForm;

@Service
@Transactional
public class TopicService {
	private static final Logger log = LoggerFactory.getLogger(TopicService.class);

	@Autowired
	private AppProperties appProperties;
	@Autowired
	private TopicRepository topicRepository;
	@Autowired
	private ChoiceRepository choiceRepository;
	@Autowired
	private VoterRepository voterRepository;

	public Topic findTopic(String hash) {
		Topic topic = topicRepository.findByHash(hash);
		if (topic == null) throw new EntityNotFoundException("トピックが存在しません [hash: " + hash + "]");
		return topic;
	}

	public Tuple2<Topic, VoteSummary> prepareShowableTopicData(String topicHash) {
		Topic topic = this.findTopic(topicHash);
		topic.setLastAccessedAt(LocalDateTime.now());
		topicRepository.saveAndFlush(topic);
		VoteSummary voteSummary = VoteSummary.of(topic);
		return Tuple2.of(topic, voteSummary);
	}

	public Topic prepareEditableTopicData(String topicHash, TopicForm topicForm) {
		Topic topic = this.findTopic(topicHash);
		BeanUtil.copy(topic, topicForm);
		return topic;
	}

	public Topic createTopic(TopicForm topicForm) {
		Topic newTopic = BeanUtil.copy(topicForm, Topic.class);
		List<Choice> choices = buildChoicesByText(newTopic, topicForm.getChoiceText());
		newTopic.setChoices(choices);
		return topicRepository.save(newTopic);
	}

	public Topic updateTopic(TopicForm topicForm, String hash) {
		if (!topicForm.getDeleteChoiceIds().isEmpty()) {
			topicForm.getDeleteChoiceIds().stream().forEach(choiceRepository::delete);
			choiceRepository.flush();
		}
		Topic topic = topicRepository.findByHash(hash);
		BeanUtil.copy(topicForm, topic);
		if (!topicForm.getChoiceText().isEmpty()) {
			List<Choice> addChoices = buildChoicesByText(topic, topicForm.getChoiceText());
			topic.getChoices().addAll(addChoices);
		}
		return topicRepository.save(topic);
	}

	public Topic deleteTopic(String hash) {
		Topic topic = this.findTopic(hash);
		topicRepository.delete(topic);
		return topic;
	}

	public Voter findVoter(String voterId) {
		Voter voter = voterRepository.findOne(Integer.valueOf(voterId));
		if (voter == null) throw new EntityNotFoundException("回答が存在しません [id: " + voterId + "]");
		return voter;
	}

	public Topic prepareAddableVoteData(String topicHash, VoteForm voteForm) {
		Topic topic = this.findTopic(topicHash);
		if (voteForm.getChoiceFeelings() == null) {
			Map<Integer, Feeling> choiceFeelings = topic.getChoices().stream().collect(
					Collectors.toMap(Choice::getId, choice -> Feeling.Unknown));
			voteForm.setChoiceFeelings(choiceFeelings);
		}
		return topic;
	}

	public Voter prepareEditableVoteData(String topicHash, String voterId, VoteForm voteForm) {
		Voter voter = this.findVoter(voterId);
		BeanUtil.copy(voter, voteForm);
		Map<Integer, Feeling> choiceFeelings = voter.getTopic().getChoices().stream().collect(
				Collectors.toMap(
						Choice::getId,
						choice -> choice.getVoterChoice(voter.getId()).map(VoterChoice::getFeeling).orElse(Feeling.Unknown)));
		voteForm.setChoiceFeelings(choiceFeelings);
		return voter;
	}

	public void createVote(String topicHash, VoteForm voteForm) {
		Topic topic = this.findTopic(topicHash);
		Voter voter = Voter.of(voteForm.getName(), voteForm.getComment(), topic);
		topic.getVoters().add(voter);
		List<VoterChoice> voterChoices = voteForm.getChoiceFeelings()
				.entrySet()
				.stream()
				.map(entry -> VoterChoice.of(entry.getValue(), voter, topic.getChoice(entry.getKey())))
				.collect(Collectors.toList());
		voter.setVoterChoices(voterChoices);
		topicRepository.save(topic);
	}

	public void updateVote(String topicHash, String voterId, VoteForm voteForm) {
		Voter voter = this.findVoter(voterId);
		BeanUtil.copy(voteForm, voter);
		voter.getVoterChoices().clear();
		voterRepository.saveAndFlush(voter);
		Topic topic = this.findTopic(topicHash);
		List<VoterChoice> voterChoices = voteForm.getChoiceFeelings()
				.entrySet()
				.stream()
				.map(entry -> VoterChoice.of(entry.getValue(), voter, topic.getChoice(entry.getKey())))
				.collect(Collectors.toList());
		voter.getVoterChoices().addAll(voterChoices);
		voterRepository.saveAndFlush(voter);
	}

	public void deleteVote(String voterId) {
		Voter voter = this.findVoter(voterId);
		voterRepository.delete(voter);
	}

	public void cleanExpiredTopics() {
		int effectiveDaysOfTopics = appProperties.getEffectiveDaysOfTopics();
		List<Topic> expiredTopics = topicRepository
				.findByLastAccessedAtLessThan(LocalDateTime.now().minusDays(effectiveDaysOfTopics));
		long beforeTopicsCount = topicRepository.count();
		expiredTopics.stream().forEach(topicRepository::delete);
		topicRepository.flush();
		long afterTopicsCount = topicRepository.count();
		String report = String.format(
				"ℹ️ %s日以上アクセスのないトピックを削除しました - トピック数(処理前): %s, 期限切れトピック数: %s, トピック数(処理後): %s",
				effectiveDaysOfTopics,
				beforeTopicsCount,
				expiredTopics.size(),
				afterTopicsCount);
		log.info(report);
	}

	private List<Choice> buildChoicesByText(Topic topic, String choiceText) {
		if (choiceText == null || choiceText.isEmpty()) return Collections.emptyList();
		return Stream.of(choiceText.split("\n")).map(text -> Choice.of(text.trim(), topic)).collect(Collectors.toList());
	}
}
