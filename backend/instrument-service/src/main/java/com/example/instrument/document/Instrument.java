package com.example.instrument.document;

import java.math.BigDecimal;
import java.time.Instant;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/**
 * A commodity instrument. The business identifier (for example GOLD-001) is
 * stored in MongoDB's _id field, which gives us a unique index for free.
 */
@Document(collection = "instruments")
public class Instrument {

    @Id
    private String id;

    @Indexed(unique = true)
    private String symbol;

    private String name;

    private InstrumentCategory category;

    @Field(targetType = FieldType.DECIMAL128)
    private BigDecimal indicativePrice;

    private boolean active = true;

    /** Incremented asynchronously for every ORDER_CREATED event consumed from Kafka. */
    private long activityCount;

    private Instant lastOrderAt;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    @Version
    private Long version;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public InstrumentCategory getCategory() {
        return category;
    }

    public void setCategory(InstrumentCategory category) {
        this.category = category;
    }

    public BigDecimal getIndicativePrice() {
        return indicativePrice;
    }

    public void setIndicativePrice(BigDecimal indicativePrice) {
        this.indicativePrice = indicativePrice;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public long getActivityCount() {
        return activityCount;
    }

    public void setActivityCount(long activityCount) {
        this.activityCount = activityCount;
    }

    public Instant getLastOrderAt() {
        return lastOrderAt;
    }

    public void setLastOrderAt(Instant lastOrderAt) {
        this.lastOrderAt = lastOrderAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }
}
