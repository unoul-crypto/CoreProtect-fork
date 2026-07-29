package net.coreprotect.command.logical;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Parsed boolean expression used by CoreProtect commands.
 */
public final class LogicalQuery {
    public enum Operator {
        TERM,
        AND,
        OR,
        NOT,
        TRUE
    }

    public static final class Node {
        private final Operator operator;
        private final String term;
        private final Node left;
        private final Node right;

        private Node(Operator operator, String term, Node left, Node right) {
            this.operator = operator;
            this.term = term;
            this.left = left;
            this.right = right;
        }

        public static Node term(String value) {
            return new Node(Operator.TERM, value, null, null);
        }

        public static Node binary(Operator operator, Node left, Node right) {
            return new Node(operator, null, left, right);
        }

        public static Node not(Node value) {
            return new Node(Operator.NOT, null, value, null);
        }

        public static Node alwaysTrue() {
            return new Node(Operator.TRUE, null, null, null);
        }

        public Operator getOperator() {
            return operator;
        }

        public String getTerm() {
            return term;
        }

        public Node getLeft() {
            return left;
        }

        public Node getRight() {
            return right;
        }
    }

    private final Node root;
    private final String source;
    private final boolean explicitOperator;

    LogicalQuery(Node root, String source, boolean explicitOperator) {
        this.root = root;
        this.source = source;
        this.explicitOperator = explicitOperator;
    }

    public static LogicalQuery parse(String[] arguments) {
        return new Parser(arguments).parse();
    }

    public Node getRoot() {
        return root;
    }

    public String getSource() {
        return source;
    }

    public boolean hasExplicitOperator() {
        return explicitOperator;
    }

    public boolean hasTermPrefix(String... prefixes) {
        List<String> normalized = new ArrayList<>();
        for (String prefix : prefixes) {
            normalized.add(prefix.toLowerCase(Locale.ROOT));
        }
        return hasTermPrefix(root, normalized);
    }

    public boolean hasTermValue(String prefix, String... values) {
        return hasTermValue(root, prefix.toLowerCase(Locale.ROOT), values);
    }

    private static boolean hasTermPrefix(Node node, List<String> prefixes) {
        if (node == null) {
            return false;
        }
        if (node.operator == Operator.TERM) {
            String term = node.term.toLowerCase(Locale.ROOT);
            for (String prefix : prefixes) {
                if (term.startsWith(prefix + ":") || term.equals(prefix + ":")) {
                    return true;
                }
            }
            return false;
        }
        return hasTermPrefix(node.left, prefixes) || hasTermPrefix(node.right, prefixes);
    }

    private static boolean hasTermValue(Node node, String prefix, String... values) {
        if (node == null) {
            return false;
        }
        if (node.operator == Operator.TERM) {
            String term = node.term.toLowerCase(Locale.ROOT);
            int separator = term.indexOf(':');
            if (separator < 0 || !term.substring(0, separator).equals(prefix)) {
                return false;
            }
            String termValue = term.substring(separator + 1);
            for (String value : values) {
                if (termValue.equals(value.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
            return false;
        }
        return hasTermValue(node.left, prefix, values) || hasTermValue(node.right, prefix, values);
    }

    private static final class Parser {
        private final List<Token> tokens;
        private final String source;
        private int position;
        private boolean explicitOperator;

        private Parser(String[] arguments) {
            StringBuilder text = new StringBuilder();
            for (int index = 1; index < arguments.length; index++) {
                if (text.length() > 0) {
                    text.append(' ');
                }
                text.append(arguments[index]);
            }
            source = text.toString();
            tokens = tokenize(source);
        }

        private LogicalQuery parse() {
            if (tokens.isEmpty()) {
                return new LogicalQuery(Node.alwaysTrue(), source, false);
            }
            Node result = parseOr();
            if (position != tokens.size()) {
                throw error("Unexpected token '" + tokens.get(position).value + "'");
            }
            return new LogicalQuery(result, source, explicitOperator);
        }

        private Node parseOr() {
            Node result = parseAnd();
            while (match(TokenType.OR)) {
                explicitOperator = true;
                result = Node.binary(Operator.OR, result, parseAnd());
            }
            return result;
        }

        private Node parseAnd() {
            Node result = parseUnary();
            while (position < tokens.size() && !check(TokenType.OR) && !check(TokenType.RIGHT_PAREN)) {
                if (match(TokenType.AND)) {
                    explicitOperator = true;
                }
                result = Node.binary(Operator.AND, result, parseUnary());
            }
            return result;
        }

        private Node parseUnary() {
            if (match(TokenType.NOT)) {
                explicitOperator = true;
                return Node.not(parseUnary());
            }
            if (match(TokenType.LEFT_PAREN)) {
                explicitOperator = true;
                Node result = parseOr();
                if (!match(TokenType.RIGHT_PAREN)) {
                    throw error("Missing closing parenthesis");
                }
                return result;
            }
            if (!match(TokenType.WORD)) {
                String token = position < tokens.size() ? tokens.get(position).value : "end of expression";
                throw error("Expected a query parameter before '" + token + "'");
            }

            String term = tokens.get(position - 1).value;
            if (term.endsWith(":") && position < tokens.size() && check(TokenType.WORD)) {
                term += tokens.get(position++).value;
            }
            return Node.term(term);
        }

        private boolean match(TokenType type) {
            if (!check(type)) {
                return false;
            }
            position++;
            return true;
        }

        private boolean check(TokenType type) {
            return position < tokens.size() && tokens.get(position).type == type;
        }

        private IllegalArgumentException error(String detail) {
            return new IllegalArgumentException("Invalid logical query: " + detail + ".");
        }

        private static List<Token> tokenize(String input) {
            if (input.trim().isEmpty()) {
                return Collections.emptyList();
            }
            List<Token> result = new ArrayList<>();
            StringBuilder word = new StringBuilder();
            for (int index = 0; index < input.length(); index++) {
                char character = input.charAt(index);
                if (Character.isWhitespace(character) || character == '(' || character == ')') {
                    addWord(result, word);
                    if (character == '(') {
                        result.add(new Token(TokenType.LEFT_PAREN, "("));
                    }
                    else if (character == ')') {
                        result.add(new Token(TokenType.RIGHT_PAREN, ")"));
                    }
                }
                else {
                    word.append(character);
                }
            }
            addWord(result, word);
            return result;
        }

        private static void addWord(List<Token> tokens, StringBuilder word) {
            if (word.length() == 0) {
                return;
            }
            String value = word.toString();
            String lower = value.toLowerCase(Locale.ROOT);
            TokenType type = TokenType.WORD;
            if (lower.equals("and")) {
                type = TokenType.AND;
            }
            else if (lower.equals("or")) {
                type = TokenType.OR;
            }
            else if (lower.equals("not")) {
                type = TokenType.NOT;
            }
            tokens.add(new Token(type, value));
            word.setLength(0);
        }
    }

    private enum TokenType {
        WORD,
        AND,
        OR,
        NOT,
        LEFT_PAREN,
        RIGHT_PAREN
    }

    private static final class Token {
        private final TokenType type;
        private final String value;

        private Token(TokenType type, String value) {
            this.type = type;
            this.value = value;
        }
    }
}
