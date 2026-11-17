package io.github.hamza2829.catalog.rules;

/** One step in the ordered product processing pipeline. */
public interface ProductRule {
    String id();

    void apply(RuleContext context);
}
