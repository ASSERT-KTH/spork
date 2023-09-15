/*
 * This file is taken from the Spoon library, see <a href="https://github.com/inria/spoon">Spoon's GitHub page</a> for
 * more info on it.
 */

/*
 * Copyright (C) INRIA and contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the
 * Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
 * WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
 * OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package se.kth.spork.spoon.printer;

import spoon.SpoonException;
import spoon.reflect.code.BinaryOperatorKind;
import spoon.reflect.code.UnaryOperatorKind;

/** Computes source code representation of the operator */
class OperatorHelper {

    private OperatorHelper() {}

    public static boolean isPrefixOperator(UnaryOperatorKind o) {
        return isSufixOperator(o) == false;
    }

    public static boolean isSufixOperator(UnaryOperatorKind o) {
        return o.name().startsWith("POST");
    }

    public static String getOperatorText(Object o) {
        if (o instanceof UnaryOperatorKind) {
            return getOperatorText((UnaryOperatorKind) o);
        } else if (o instanceof BinaryOperatorKind) {
            return getOperatorText((BinaryOperatorKind) o);
        } else {
            throw new IllegalArgumentException("Can't get operator text for type " + o.getClass());
        }
    }

    /**
     * @return java source code representation of a pre or post unary operator.
     */
    public static String getOperatorText(UnaryOperatorKind o) {
        switch (o) {
            case POS:
                return "+";
            case NEG:
                return "-";
            case NOT:
                return "!";
            case COMPL:
                return "~";
            case PREINC:
                return "++";
            case PREDEC:
                return "--";
            case POSTINC:
                return "++";
            case POSTDEC:
                return "--";
            default:
                throw new SpoonException("Unsupported operator " + o.name());
        }
    }

    /**
     * @return java source code representation of a binary operator.
     */
    public static String getOperatorText(BinaryOperatorKind o) {
        switch (o) {
            case OR:
                return "||";
            case AND:
                return "&&";
            case BITOR:
                return "|";
            case BITXOR:
                return "^";
            case BITAND:
                return "&";
            case EQ:
                return "==";
            case NE:
                return "!=";
            case LT:
                return "<";
            case GT:
                return ">";
            case LE:
                return "<=";
            case GE:
                return ">=";
            case SL:
                return "<<";
            case SR:
                return ">>";
            case USR:
                return ">>>";
            case PLUS:
                return "+";
            case MINUS:
                return "-";
            case MUL:
                return "*";
            case DIV:
                return "/";
            case MOD:
                return "%";
            case INSTANCEOF:
                return "instanceof";
            default:
                throw new SpoonException("Unsupported operator " + o.name());
        }
    }
}
