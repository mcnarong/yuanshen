package org.yuanshen.yuanshen;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ReactionResult {
    public static final class ReactionStage {
        private final String reactionName;
        private String reactionTag;
        private String displayNote;
        private final List<String> consumedElements = new ArrayList<>();
        private double customConsumeAmount = -1;
        private boolean noConsume = false;

        private ReactionStage(String reactionName) {
            this.reactionName = reactionName;
        }

        public String getReactionName() {
            return reactionName;
        }

        public String getReactionTag() {
            return reactionTag;
        }

        public String getDisplayNote() {
            return displayNote;
        }

        public List<String> getConsumedElements() {
            return Collections.unmodifiableList(consumedElements);
        }

        public double getCustomConsumeAmount() {
            return customConsumeAmount;
        }

        public boolean isNoConsume() {
            return noConsume;
        }
    }

    public static final class TransformativeDamageEntry {
        private final double damage;
        private final String element;
        private final String reactionKey;

        private TransformativeDamageEntry(double damage, String element, String reactionKey) {
            this.damage = damage;
            this.element = element;
            this.reactionKey = reactionKey;
        }

        public double getDamage() {
            return damage;
        }

        public String getElement() {
            return element;
        }

        public String getReactionKey() {
            return reactionKey;
        }
    }

    private String reactionName;
    private String reactionTag;
    private String normalDamageElement;
    private String damageElement;
    private String displayNote;
    private double scalingDamageBonus;
    private double additiveDamageBonus;
    private double transformativeDamage;
    private String transformativeDamageElement;
    private final List<TransformativeDamageEntry> transformativeDamageEntries;
    private List<String> messages;
    private List<String> consumedElements;
    private final List<ReactionStage> reactionStages;
    private ReactionStage currentReactionStage;
    private boolean hasReaction;
    private boolean applyTriggerAura = true;
    private double triggerAuraAmount = 1.0;
    private boolean explicitTriggerAuraDecision = false;
    private boolean explicitTriggerAuraAmount = false;
    
    // 新增字段
    private boolean isNormalDamage = false;
    private boolean isCrit = false;
    
    // 新增：自定义消耗量和是否不消耗
    private double customConsumeAmount = -1;
    private boolean noConsume = false;

    public ReactionResult() {
        this.messages = new ArrayList<>();
        this.consumedElements = new ArrayList<>();
        this.reactionStages = new ArrayList<>();
        this.transformativeDamageEntries = new ArrayList<>();
    }

    public void addScalingDamage(double damage) { this.scalingDamageBonus += Math.max(0.0, damage); }
    public double getScalingDamageBonus() { return scalingDamageBonus; }

    public void addAdditiveDamage(double damage) { this.additiveDamageBonus += Math.max(0.0, damage); }
    public double getAdditiveDamageBonus() { return additiveDamageBonus; }

    public void addTransformativeDamage(double damage, String element) {
        addTransformativeDamage(damage, element, null);
    }

    public void addTransformativeDamage(double damage, String element, String reactionKey) {
        double safeDamage = Math.max(0.0, damage);
        if (safeDamage <= 0.0) {
            return;
        }

        this.transformativeDamage += safeDamage;
        this.transformativeDamageEntries.add(new TransformativeDamageEntry(safeDamage, element, reactionKey));
        if (element != null && !element.isBlank()) {
            if (this.transformativeDamageElement == null || this.transformativeDamageElement.isBlank()) {
                this.transformativeDamageElement = element;
            } else if (!this.transformativeDamageElement.equals(element)) {
                this.transformativeDamageElement = null;
            }
        }
    }

    public double getTransformativeDamage() { return transformativeDamage; }
    public String getTransformativeDamageElement() { return transformativeDamageElement; }
    public List<TransformativeDamageEntry> getTransformativeDamageEntries() {
        return Collections.unmodifiableList(transformativeDamageEntries);
    }
    public boolean hasTransformativeDamage() { return transformativeDamage > 0.0 || !transformativeDamageEntries.isEmpty(); }

    public double getAdditionalDamage() {
        return scalingDamageBonus + additiveDamageBonus + transformativeDamage;
    }

    public void setReactionName(String name) {
        this.reactionName = name;
        this.hasReaction = name != null && !name.isBlank();
        if (!this.hasReaction) {
            this.currentReactionStage = null;
            return;
        }
        this.currentReactionStage = new ReactionStage(name);
        this.reactionStages.add(this.currentReactionStage);
    }

    public String getReactionName() {
        if (reactionStages.isEmpty()) {
            return reactionName;
        }
        if (reactionStages.size() == 1) {
            return reactionStages.get(0).getReactionName();
        }
        StringBuilder builder = new StringBuilder();
        for (ReactionStage stage : reactionStages) {
            if (builder.length() > 0) {
                builder.append(" + ");
            }
            builder.append(stage.getReactionName());
        }
        return builder.toString();
    }

    public String getPrimaryReactionName() {
        if (!reactionStages.isEmpty()) {
            return reactionStages.get(0).getReactionName();
        }
        return reactionName;
    }

    public void setReactionTag(String tag) {
        this.reactionTag = tag;
        if (currentReactionStage != null) {
            currentReactionStage.reactionTag = tag;
        }
    }

    public String getReactionTag() {
        if (reactionStages.size() > 1) {
            return "&6[多重反应]";
        }
        if (!reactionStages.isEmpty() && reactionStages.get(0).getReactionTag() != null) {
            return reactionStages.get(0).getReactionTag();
        }
        return reactionTag;
    }

    public void addMessage(String message) { this.messages.add(message); }
    public List<String> getMessages() { return messages; }

    public void addConsumedElement(String element) {
        this.consumedElements.add(element);
        if (currentReactionStage != null && element != null && !element.isBlank()) {
            currentReactionStage.consumedElements.add(element);
        }
    }
    public List<String> getConsumedElements() { return consumedElements; }
    public List<ReactionStage> getReactionStages() { return Collections.unmodifiableList(reactionStages); }

    public void setNormalDamage(boolean normal) {
        this.isNormalDamage = normal;
        if (normal) {
            scalingDamageBonus = 0.0;
            additiveDamageBonus = 0.0;
            transformativeDamage = 0.0;
            transformativeDamageElement = null;
            transformativeDamageEntries.clear();
        }
    }
    public boolean isNormalDamage() { return this.isNormalDamage; }

    public void setNormalDamageElement(String normalDamageElement) {
        this.normalDamageElement = normalDamageElement;
    }

    public String getNormalDamageElement() {
        return normalDamageElement;
    }

    public void setDamageElement(String damageElement) {
        this.damageElement = damageElement;
    }

    public String getDamageElement() {
        return damageElement;
    }

    public void setDisplayNote(String displayNote) {
        this.displayNote = displayNote;
        if (currentReactionStage != null) {
            currentReactionStage.displayNote = displayNote;
        }
    }

    public String getDisplayNote() {
        if (reactionStages.size() > 1) {
            StringBuilder builder = new StringBuilder();
            for (ReactionStage stage : reactionStages) {
                String note = stage.getDisplayNote();
                if (note == null || note.isBlank()) {
                    continue;
                }
                if (builder.length() > 0) {
                    builder.append(" | ");
                }
                builder.append(note);
            }
            if (builder.length() > 0) {
                return builder.toString();
            }
        }
        return displayNote;
    }

    public boolean hasReaction() {
        return (!reactionStages.isEmpty()) || (hasReaction && reactionName != null && !reactionName.isEmpty());
    }

    public boolean shouldApplyTriggerAura() {
        return applyTriggerAura;
    }

    public void setApplyTriggerAura(boolean applyTriggerAura) {
        if (!explicitTriggerAuraDecision) {
            this.applyTriggerAura = applyTriggerAura;
            this.explicitTriggerAuraDecision = true;
            return;
        }
        this.applyTriggerAura = this.applyTriggerAura || applyTriggerAura;
    }

    public double getTriggerAuraAmount() {
        return triggerAuraAmount;
    }

    public void setTriggerAuraAmount(double triggerAuraAmount) {
        double safeAmount = Math.max(0.0, triggerAuraAmount);
        if (!explicitTriggerAuraAmount) {
            this.triggerAuraAmount = safeAmount;
            this.explicitTriggerAuraAmount = true;
            return;
        }
        this.triggerAuraAmount = Math.min(this.triggerAuraAmount, safeAmount);
    }

    public boolean isCrit() { return this.isCrit; }
    public void setCrit(boolean crit) { this.isCrit = crit; }
    
    public void setCustomConsumeAmount(double amount) {
        this.customConsumeAmount = amount;
        if (currentReactionStage != null) {
            currentReactionStage.customConsumeAmount = amount;
        }
    }
    
    public double getCustomConsumeAmount() {
        return this.customConsumeAmount;
    }
    
    public void setNoConsume(boolean noConsume) {
        this.noConsume = noConsume;
        if (currentReactionStage != null) {
            currentReactionStage.noConsume = noConsume;
        }
    }
    
    public boolean isNoConsume() {
        if (!reactionStages.isEmpty()) {
            for (ReactionStage stage : reactionStages) {
                if (!stage.isNoConsume()) {
                    return false;
                }
            }
            return true;
        }
        return this.noConsume;
    }
}
