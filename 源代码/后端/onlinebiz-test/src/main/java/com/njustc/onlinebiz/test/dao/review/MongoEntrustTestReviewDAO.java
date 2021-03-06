package com.njustc.onlinebiz.test.dao.review;

import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.UpdateResult;
import com.njustc.onlinebiz.common.model.test.review.EntrustTestReview;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

@Component
public class MongoEntrustTestReviewDAO implements EntrustTestReviewDAO {
    public static final String COLLECTION_NAME = "entrustTestReview";
    private final MongoTemplate mongoTemplate;

    public MongoEntrustTestReviewDAO(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }
    @Override
    public EntrustTestReview insertEntrustTestReview(EntrustTestReview entrustTestReview) {
        return mongoTemplate.insert(entrustTestReview, COLLECTION_NAME);
    }

    @Override
    public EntrustTestReview findEntrustTestReviewById(String entrustTestReviewId) {
        return mongoTemplate.findById(entrustTestReviewId, EntrustTestReview.class, COLLECTION_NAME);
    }

    @Override
    public Boolean updateEntrustTestReview(String entrustTestReviewId, EntrustTestReview entrustTestReview) {
        Update update = new Update()
                .set("softwareName", entrustTestReview.getSoftwareName())
                .set("version", entrustTestReview.getVersion())
                .set("principal", entrustTestReview.getPrincipal())
                .set("startDate", entrustTestReview.getStartDate())
                .set("expectFinishDate", entrustTestReview.getExpectFinishDate())
                .set("mainTester", entrustTestReview.getMainTester())
                .set("finishDate", entrustTestReview.getFinishDate())
                .set("conclusions", entrustTestReview.getConclusions());
        return updateFirstWithId(entrustTestReviewId, update);
    }

    @Override
    public Boolean deleteEntrustTestReviewById(String entrustTestReviewId) {
        Query query = new Query().addCriteria(Criteria.where("_id").is(new ObjectId(entrustTestReviewId)));
        DeleteResult result = mongoTemplate.remove(query, COLLECTION_NAME);
        return result.wasAcknowledged() && result.getDeletedCount() == 1;
    }

    // ?????????????????? id ???????????????????????????????????? Update ??????????????????????????????
    private Boolean updateFirstWithId(String reportReviewId, Update update) {
        Query query = new Query().addCriteria(Criteria.where("_id").is(new ObjectId(reportReviewId)));
        UpdateResult result = mongoTemplate.updateFirst(query, update, COLLECTION_NAME);
        return result.wasAcknowledged() && result.getMatchedCount() == 1;
    }
}
