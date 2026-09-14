package com.tinyquery.parser;

public record SimpleCondition(String field, String op, String value) implements Condition {
}
