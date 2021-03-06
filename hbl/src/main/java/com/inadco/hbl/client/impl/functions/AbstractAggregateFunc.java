/*
 * 
 *  Copyright © 2010, 2011 Inadco, Inc. All rights reserved.
 *  
 *     Licensed under the Apache License, Version 2.0 (the "License");
 *     you may not use this file except in compliance with the License.
 *     You may obtain a copy of the License at
 *  
 *         http://www.apache.org/licenses/LICENSE-2.0
 *  
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS,
 *     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *     See the License for the specific language governing permissions and
 *     limitations under the License.
 *  
 *  
 */
package com.inadco.hbl.client.impl.functions;

import com.inadco.hbl.api.AggregateFunction;
import com.inadco.hbl.api.AggregateFunctionRegistry;
import com.inadco.hbl.client.impl.SliceOperation;
import com.inadco.hbl.protocodegen.Cells.Aggregation;
import com.inadco.hbl.protocodegen.Cells.Aggregation.Builder;

/**
 * common suggested base for all aggregate function implementations
 * 
 * @author dmitriy
 * 
 */
public abstract class AbstractAggregateFunc implements AggregateFunction {

    protected String                    name;
    protected AggregateFunctionRegistry parent;

    protected AbstractAggregateFunc(String name) {
        super();
        this.name = name;
    }

    @Override
    public void init(AggregateFunctionRegistry parent) {
        this.parent = parent;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public boolean supportsComplementScan() {
        return true;
    }

    @Override
    public void apply(Builder result, Object measureFact) {

    }

    @Override
    public void merge(Builder accumulator, Aggregation source, SliceOperation operation) {

    }

}
