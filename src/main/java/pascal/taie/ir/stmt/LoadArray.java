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

import pascal.taie.ir.exp.ArrayAccess;
import pascal.taie.ir.exp.Var;

/**
 * Representation of load array statement, e.g., x = a[..].
 */
public class LoadArray extends AssignStmt<Var, ArrayAccess> {

    public LoadArray(Var lvalue, ArrayAccess rvalue) {
        super(lvalue, rvalue);
    }
}
