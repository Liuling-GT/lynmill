package com.tinyquery.parser.where;

public record OrCondition(Condition left, Condition right) implements Condition {
}
