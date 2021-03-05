/*
 * Tai-e: A Program Analysis Framework for Java
 *
 * Copyright (C) 2020 Tian Tan <tiantan@nju.edu.cn>
 * Copyright (C) 2020 Yue Li <yueli@nju.edu.cn>
 * All rights reserved.
 *
 * This software is designed for the "Static Program Analysis" course at
 * Nanjing University, and it supports a subset of Java features.
 * Tai-e is only for educational and academic purposes, and any form of
 * commercial use is disallowed.
 */

package pascal.taie.ir.stmt;

import pascal.taie.ir.exp.NewExp;
import pascal.taie.ir.exp.Var;

/**
 * Representation of following kinds of new statements:
 * - new instance: o = new T
 * - new array: o = new T[..]
 * - new multi-array: o = new T[..][..]
 */
public class New extends AssignStmt<Var, NewExp> {

    public New(Var lvalue, NewExp rvalue) {
        super(lvalue, rvalue);
    }
}
