/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */
package org.apache.groovy.ginq.provider.collection

import groovy.transform.CompileStatic
import org.apache.groovy.ginq.dsl.GinqAstVisitor
import org.apache.groovy.ginq.dsl.GinqSyntaxError
import org.apache.groovy.ginq.dsl.SyntaxErrorReportable
import org.apache.groovy.ginq.dsl.expression.AbstractGinqExpression
import org.apache.groovy.ginq.dsl.expression.DataSourceExpression
import org.apache.groovy.ginq.dsl.expression.FromExpression
import org.apache.groovy.ginq.dsl.expression.GinqExpression
import org.apache.groovy.ginq.dsl.expression.GroupExpression
import org.apache.groovy.ginq.dsl.expression.HavingExpression
import org.apache.groovy.ginq.dsl.expression.JoinExpression
import org.apache.groovy.ginq.dsl.expression.LimitExpression
import org.apache.groovy.ginq.dsl.expression.OnExpression
import org.apache.groovy.ginq.dsl.expression.OrderExpression
import org.apache.groovy.ginq.dsl.expression.SelectExpression
import org.apache.groovy.ginq.dsl.expression.WhereExpression
import org.apache.groovy.ginq.provider.collection.runtime.NamedRecord
import org.apache.groovy.ginq.provider.collection.runtime.Queryable
import org.apache.groovy.ginq.provider.collection.runtime.QueryableHelper
import org.apache.groovy.util.Maps
import org.codehaus.groovy.GroovyBugError
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.CodeVisitorSupport
import org.codehaus.groovy.ast.expr.ArgumentListExpression
import org.codehaus.groovy.ast.expr.BinaryExpression
import org.codehaus.groovy.ast.expr.CastExpression
import org.codehaus.groovy.ast.expr.ClassExpression
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.ConstructorCallExpression
import org.codehaus.groovy.ast.expr.DeclarationExpression
import org.codehaus.groovy.ast.expr.EmptyExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.ExpressionTransformer
import org.codehaus.groovy.ast.expr.GStringExpression
import org.codehaus.groovy.ast.expr.LambdaExpression
import org.codehaus.groovy.ast.expr.ListExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.PostfixExpression
import org.codehaus.groovy.ast.expr.PropertyExpression
import org.codehaus.groovy.ast.expr.TupleExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.ast.stmt.Statement
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.syntax.Token
import org.codehaus.groovy.syntax.Types
import org.objectweb.asm.Opcodes

import java.util.stream.Collectors

import static org.codehaus.groovy.ast.ClassHelper.makeWithoutCaching
import static org.codehaus.groovy.ast.tools.GeneralUtils.args
import static org.codehaus.groovy.ast.tools.GeneralUtils.block
import static org.codehaus.groovy.ast.tools.GeneralUtils.callX
import static org.codehaus.groovy.ast.tools.GeneralUtils.ctorX
import static org.codehaus.groovy.ast.tools.GeneralUtils.declS
import static org.codehaus.groovy.ast.tools.GeneralUtils.declX
import static org.codehaus.groovy.ast.tools.GeneralUtils.lambdaX
import static org.codehaus.groovy.ast.tools.GeneralUtils.localVarX
import static org.codehaus.groovy.ast.tools.GeneralUtils.param
import static org.codehaus.groovy.ast.tools.GeneralUtils.params
import static org.codehaus.groovy.ast.tools.GeneralUtils.propX
import static org.codehaus.groovy.ast.tools.GeneralUtils.stmt
import static org.codehaus.groovy.ast.tools.GeneralUtils.varX

/**
 * Visit AST of GINQ to generate target method calls for GINQ
 *
 * @since 4.0.0
 */
@CompileStatic
class GinqAstWalker implements GinqAstVisitor<Expression>, SyntaxErrorReportable {

    GinqAstWalker(SourceUnit sourceUnit) {
        this.sourceUnit = sourceUnit
    }

    private GinqExpression getCurrentGinqExpression() {
        ginqExpressionStack.peek()
    }

    @Override
    MethodCallExpression visitGinqExpression(GinqExpression ginqExpression) {
        if (!ginqExpression) {
            this.collectSyntaxError(new GinqSyntaxError("`select` clause is missing", -1, -1))
        }

        ginqExpressionStack.push(ginqExpression)

        DataSourceExpression resultDataSourceExpression
        MethodCallExpression resultMethodCallReceiver

        FromExpression fromExpression = currentGinqExpression.fromExpression
        resultDataSourceExpression = fromExpression
        MethodCallExpression fromMethodCallExpression = this.visitFromExpression(fromExpression)
        resultMethodCallReceiver = fromMethodCallExpression

        for (JoinExpression joinExpression : currentGinqExpression.joinExpressionList) {
            joinExpression.putNodeMetaData(__METHOD_CALL_RECEIVER, resultMethodCallReceiver)
            joinExpression.dataSourceExpression = resultDataSourceExpression

            resultDataSourceExpression = joinExpression
            resultMethodCallReceiver = this.visitJoinExpression(resultDataSourceExpression)
        }

        WhereExpression whereExpression = currentGinqExpression.whereExpression
        if (whereExpression) {
            whereExpression.dataSourceExpression = resultDataSourceExpression
            whereExpression.putNodeMetaData(__METHOD_CALL_RECEIVER, resultMethodCallReceiver)
            MethodCallExpression whereMethodCallExpression = visitWhereExpression(whereExpression)
            resultMethodCallReceiver = whereMethodCallExpression
        }

        GroupExpression groupExpression = currentGinqExpression.groupExpression
        if (groupExpression) {
            groupExpression.dataSourceExpression = resultDataSourceExpression
            groupExpression.putNodeMetaData(__METHOD_CALL_RECEIVER, resultMethodCallReceiver)
            MethodCallExpression groupMethodCallExpression = visitGroupExpression(groupExpression)
            resultMethodCallReceiver = groupMethodCallExpression
        }

        OrderExpression orderExpression = currentGinqExpression.orderExpression
        if (orderExpression) {
            orderExpression.dataSourceExpression = resultDataSourceExpression
            orderExpression.putNodeMetaData(__METHOD_CALL_RECEIVER, resultMethodCallReceiver)
            MethodCallExpression orderMethodCallExpression = visitOrderExpression(orderExpression)
            resultMethodCallReceiver = orderMethodCallExpression
        }

        LimitExpression limitExpression = currentGinqExpression.limitExpression
        if (limitExpression) {
            limitExpression.dataSourceExpression = resultDataSourceExpression
            limitExpression.putNodeMetaData(__METHOD_CALL_RECEIVER, resultMethodCallReceiver)
            MethodCallExpression limitMethodCallExpression = visitLimitExpression(limitExpression)
            resultMethodCallReceiver = limitMethodCallExpression
        }

        SelectExpression selectExpression = currentGinqExpression.selectExpression
        selectExpression.putNodeMetaData(__METHOD_CALL_RECEIVER, resultMethodCallReceiver)
        selectExpression.dataSourceExpression = resultDataSourceExpression

        MethodCallExpression selectMethodCallExpression = this.visitSelectExpression(selectExpression)

        List<Statement> statementList = []
        def metaDataMapVar = localVarX(metaDataMapName)
        metaDataMapVar.modifiers = metaDataMapVar.modifiers | Opcodes.ACC_FINAL
        statementList << declS(
                metaDataMapVar,
                callX(MAPS_TYPE, "of", args(
                        new ConstantExpression(MD_ALIAS_NAME_LIST), aliasNameListExpression,
                        new ConstantExpression(MD_GROUP_NAME_LIST), groupNameListExpression,
                        new ConstantExpression(MD_SELECT_NAME_LIST), selectNameListExpression
                ))
        )
        if (rowNumberUsed) {
            statementList << declS(localVarX(rowNumberName), new ConstantExpression(0L))
        }
        statementList << stmt(selectMethodCallExpression)

        def result = callX(lambdaX(block(statementList as Statement[])), "call")

        ginqExpressionStack.pop()
        return result
    }

    @Override
    MethodCallExpression visitFromExpression(FromExpression fromExpression) {
        MethodCallExpression fromMethodCallExpression = constructFromMethodCallExpression(fromExpression.dataSourceExpr)
        fromMethodCallExpression.setSourcePosition(fromExpression)

        return fromMethodCallExpression
    }

    @Override
    MethodCallExpression visitJoinExpression(JoinExpression joinExpression) {
        Expression receiver = joinExpression.getNodeMetaData(__METHOD_CALL_RECEIVER)
        OnExpression onExpression = joinExpression.onExpression

        if (!onExpression && !joinExpression.crossJoin) {
            this.collectSyntaxError(
                    new GinqSyntaxError(
                            "`on` clause is expected for `" + joinExpression.joinName + "`",
                            joinExpression.getLineNumber(), joinExpression.getColumnNumber()
                    )
            )
        }

        MethodCallExpression joinMethodCallExpression = constructJoinMethodCallExpression(receiver, joinExpression, onExpression)
        joinMethodCallExpression.setSourcePosition(joinExpression)

        return joinMethodCallExpression
    }

    @Override
    MethodCallExpression visitOnExpression(OnExpression onExpression) {
        return null // do nothing
    }

    private MethodCallExpression constructFromMethodCallExpression(Expression dataSourceExpr) {
        callX(
                makeQueryableCollectionClassExpression(),
                "from",
                args(
                        dataSourceExpr instanceof AbstractGinqExpression
                                ? this.visit((AbstractGinqExpression) dataSourceExpr)
                                : dataSourceExpr
                )
        )
    }

    private MethodCallExpression constructJoinMethodCallExpression(
            Expression receiver, JoinExpression joinExpression,
            OnExpression onExpression) {

        DataSourceExpression otherDataSourceExpression = joinExpression.dataSourceExpression
        Expression otherAliasExpr = otherDataSourceExpression.aliasExpr

        String otherParamName = otherAliasExpr.text
        List<DeclarationExpression> declarationExpressionList = Collections.emptyList()
        Expression filterExpr = EmptyExpression.INSTANCE
        if (onExpression) {
            filterExpr = onExpression.getFilterExpr()
            Tuple3<String, List<DeclarationExpression>, Expression> paramNameAndLambdaCode = correctVariablesOfLambdaExpression(otherDataSourceExpression, filterExpr)
            otherParamName = paramNameAndLambdaCode.v1
            declarationExpressionList = paramNameAndLambdaCode.v2
            filterExpr = paramNameAndLambdaCode.v3
        }

        List<Statement> statementList = []
        statementList.addAll(declarationExpressionList.stream().map(e -> stmt(e)).collect(Collectors.toList()))
        statementList.add(stmt(filterExpr))

        MethodCallExpression resultMethodCallExpression
        MethodCallExpression joinMethodCallExpression = callX(receiver, joinExpression.joinName.replace('join', 'Join'),
                args(
                        constructFromMethodCallExpression(joinExpression.dataSourceExpr),
                        null == onExpression ? EmptyExpression.INSTANCE : lambdaX(
                                params(
                                        param(ClassHelper.DYNAMIC_TYPE, otherParamName),
                                        param(ClassHelper.DYNAMIC_TYPE, joinExpression.aliasExpr.text)
                                ),
                                block(statementList as Statement[])
                        )
                )
        )
        resultMethodCallExpression = joinMethodCallExpression

        if (joinExpression.crossJoin) {
            // cross join does not need `on` clause
            Expression lastArgumentExpression = ((ArgumentListExpression) joinMethodCallExpression.arguments).getExpressions().removeLast()
            if (EmptyExpression.INSTANCE !== lastArgumentExpression) {
                throw new GroovyBugError("Wrong argument removed")
            }
        }

        return resultMethodCallExpression
    }

    @Override
    MethodCallExpression visitWhereExpression(WhereExpression whereExpression) {
        DataSourceExpression dataSourceExpression = whereExpression.dataSourceExpression
        Expression fromMethodCallExpression = whereExpression.getNodeMetaData(__METHOD_CALL_RECEIVER)
        Expression filterExpr = whereExpression.getFilterExpr()

        // construct the `ListExpression` instance to transform `filterExpr` in the same time
        filterExpr = ((ListExpression) new ListExpression(Collections.singletonList(filterExpr)).transformExpression(
                new ExpressionTransformer() {
                    @Override
                    Expression transform(Expression expression) {
                        if (expression instanceof AbstractGinqExpression) {
                            def ginqExpression = GinqAstWalker.this.visit((AbstractGinqExpression) expression)
                            return ginqExpression
                        }

                        if (expression instanceof BinaryExpression) {
                            if (expression.operation.type == Types.KEYWORD_IN) {
                                if (expression.rightExpression instanceof AbstractGinqExpression) {
                                    expression.rightExpression =
                                            callX(GinqAstWalker.this.visit((AbstractGinqExpression) expression.rightExpression),
                                                    "toList")
                                    return expression
                                }
                            }
                        }

                        return expression.transformExpression(this)
                    }
                }
        )).getExpression(0)

        def whereMethodCallExpression = callXWithLambda(fromMethodCallExpression, "where", dataSourceExpression, filterExpr)
        whereMethodCallExpression.setSourcePosition(whereExpression)

        return whereMethodCallExpression
    }

    @Override
    MethodCallExpression visitGroupExpression(GroupExpression groupExpression) {
        DataSourceExpression dataSourceExpression = groupExpression.dataSourceExpression
        Expression groupMethodCallReceiver = groupExpression.getNodeMetaData(__METHOD_CALL_RECEIVER)
        Expression classifierExpr = groupExpression.classifierExpr

        List<Expression> argumentExpressionList = ((ArgumentListExpression) classifierExpr).getExpressions()
        ConstructorCallExpression namedListCtorCallExpression = constructNamedRecordCtorCallExpression(argumentExpressionList, MD_GROUP_NAME_LIST)

        LambdaExpression classifierLambdaExpression = constructLambdaExpression(dataSourceExpression, namedListCtorCallExpression)

        List<Expression> argList = new ArrayList<>()
        argList << classifierLambdaExpression

        this.currentGinqExpression.putNodeMetaData(__GROUPBY_VISITED, true)

        HavingExpression havingExpression = groupExpression.havingExpression
        if (havingExpression) {
            Expression filterExpr = havingExpression.filterExpr
            LambdaExpression havingLambdaExpression = constructLambdaExpression(dataSourceExpression, filterExpr)
            argList << havingLambdaExpression
        }

        MethodCallExpression groupMethodCallExpression = callX(groupMethodCallReceiver, "groupBy", args(argList))
        groupMethodCallExpression.setSourcePosition(groupExpression)

        return groupMethodCallExpression
    }

    @Override
    Expression visitHavingExpression(HavingExpression havingExpression) {
        return null // do nothing
    }

    @Override
    MethodCallExpression visitOrderExpression(OrderExpression orderExpression) {
        DataSourceExpression dataSourceExpression = orderExpression.dataSourceExpression
        Expression orderMethodCallReceiver = orderExpression.getNodeMetaData(__METHOD_CALL_RECEIVER)
        Expression ordersExpr = orderExpression.ordersExpr

        List<Expression> argumentExpressionList = ((ArgumentListExpression) ordersExpr).getExpressions()
        List<Expression> orderCtorCallExpressions = argumentExpressionList.stream().map(e -> {
            Expression target = e
            boolean asc = true
            if (e instanceof BinaryExpression && e.operation.type == Types.KEYWORD_IN) {
                target = e.leftExpression

                String orderOption = e.rightExpression.text
                if (!ORDER_OPTION_LIST.contains(orderOption)) {
                    this.collectSyntaxError(
                            new GinqSyntaxError(
                                    "Invalid order: " + orderOption + ", `asc`/`desc` is expected",
                                    e.rightExpression.getLineNumber(), e.rightExpression.getColumnNumber()
                            )
                    )
                }

                asc = 'asc' == orderOption
            }

            LambdaExpression lambdaExpression = constructLambdaExpression(dataSourceExpression, target)

            return ctorX(ORDER_TYPE, args(lambdaExpression, new ConstantExpression(asc)))
        }).collect(Collectors.toList())

        def orderMethodCallExpression = callX(orderMethodCallReceiver, "orderBy", args(orderCtorCallExpressions))
        orderMethodCallExpression.setSourcePosition(orderExpression)

        return orderMethodCallExpression
    }

    @Override
    MethodCallExpression visitLimitExpression(LimitExpression limitExpression) {
        Expression limitMethodCallReceiver = limitExpression.getNodeMetaData(__METHOD_CALL_RECEIVER)
        Expression offsetAndSizeExpr = limitExpression.offsetAndSizeExpr

        def limitMethodCallExpression = callX(limitMethodCallReceiver, "limit", offsetAndSizeExpr)
        limitMethodCallExpression.setSourcePosition(limitExpression)

        return limitMethodCallExpression
    }

    @Override
    MethodCallExpression visitSelectExpression(SelectExpression selectExpression) {
        currentGinqExpression.putNodeMetaData(__VISITING_SELECT, true)
        Expression selectMethodReceiver = selectExpression.getNodeMetaData(__METHOD_CALL_RECEIVER)
        DataSourceExpression dataSourceExpression = selectExpression.dataSourceExpression
        Expression projectionExpr = selectExpression.getProjectionExpr()

        List<Expression> expressionList = ((TupleExpression) projectionExpr).getExpressions()
        Expression lambdaCode = expressionList.get(0)
        def expressionListSize = expressionList.size()
        if (expressionListSize > 1 || (expressionListSize == 1 && lambdaCode instanceof CastExpression)) {
            ConstructorCallExpression namedListCtorCallExpression = constructNamedRecordCtorCallExpression(expressionList, MD_SELECT_NAME_LIST)
            lambdaCode = namedListCtorCallExpression
        }

        lambdaCode = lambdaCode.transformExpression(new ExpressionTransformer() {
            @Override
            Expression transform(Expression expression) {
                if (expression instanceof VariableExpression) {
                    if (_RN == expression.text) {
                        currentGinqExpression.putNodeMetaData(__RN_USED, true)
                        return new PostfixExpression(varX(rowNumberName), new Token(Types.PLUS_PLUS, '++', -1, -1))
                    }
                }

                return expression.transformExpression(this)
            }
        })

        def selectMethodCallExpression = callXWithLambda(selectMethodReceiver, "select", dataSourceExpression, lambdaCode)

        currentGinqExpression.putNodeMetaData(__VISITING_SELECT, false)

        return selectMethodCallExpression
    }

    private ConstructorCallExpression constructNamedRecordCtorCallExpression(List<Expression> expressionList, String metaDataKey) {
        int expressionListSize = expressionList.size()
        List<Expression> elementExpressionList = new ArrayList<>(expressionListSize)
        List<Expression> nameExpressionList = new ArrayList<>(expressionListSize)
        for (Expression e : expressionList) {
            Expression elementExpression = e
            Expression nameExpression = new ConstantExpression(e.text)

            if (e instanceof CastExpression) {
                elementExpression = e.expression
                nameExpression = new ConstantExpression(e.type.text)
            } else if (e instanceof PropertyExpression) {
                if (e.property instanceof ConstantExpression) {
                    elementExpression = e
                    nameExpression = new ConstantExpression(e.property.text)
                } else if (e.property instanceof GStringExpression) {
                    elementExpression = e
                    nameExpression = e.property
                }
            }
            elementExpressionList << elementExpression
            nameExpressionList << nameExpression
        }

        def nameListExpression = new ListExpression(nameExpressionList)
        currentGinqExpression.putNodeMetaData(metaDataKey, nameListExpression)

        ConstructorCallExpression namedRecordCtorCallExpression =
                ctorX(NAMED_RECORD_TYPE,
                        args(
                                new ListExpression(elementExpressionList),
                                getMetaDataMethodCall(metaDataKey), getMetaDataMethodCall(MD_ALIAS_NAME_LIST)
                        )
                )
        return namedRecordCtorCallExpression
    }

    private int metaDataMapNameSeq = 0
    private String getMetaDataMapName() {
        String name = (String) currentGinqExpression.getNodeMetaData(__META_DATA_MAP_NAME_PREFIX)

        if (!name) {
            name = "${__META_DATA_MAP_NAME_PREFIX}${metaDataMapNameSeq++}"
            currentGinqExpression.putNodeMetaData(__META_DATA_MAP_NAME_PREFIX, name)
        }

        return name
    }

    private int rowNumberNameSeq = 0
    private String getRowNumberName() {
        String name = (String) currentGinqExpression.getNodeMetaData(__ROW_NUMBER_NAME_PREFIX)

        if (!name) {
            name = "${__ROW_NUMBER_NAME_PREFIX}${rowNumberNameSeq++}"
            currentGinqExpression.putNodeMetaData(__ROW_NUMBER_NAME_PREFIX, name)
        }

        return name
    }

    private MethodCallExpression getMetaDataMethodCall(String key) {
        callX(varX(metaDataMapName), "get", new ConstantExpression(key))
    }

    private MethodCallExpression putMetaDataMethodCall(String key, Expression value) {
        callX(varX(metaDataMapName), "put", args(new ConstantExpression(key), value))
    }

    private ListExpression getSelectNameListExpression() {
        return (ListExpression) (currentGinqExpression.getNodeMetaData(MD_SELECT_NAME_LIST) ?: [])
    }

    private ListExpression getGroupNameListExpression() {
        return (ListExpression) (currentGinqExpression.getNodeMetaData(MD_GROUP_NAME_LIST) ?: [])
    }

    private ListExpression getAliasNameListExpression() {
        return new ListExpression(aliasExpressionList)
    }

    private List<Expression> getAliasExpressionList() {
        dataSourceAliasList.stream()
                .map(e -> new ConstantExpression(e))
                .collect(Collectors.toList())
    }

    private List<String> getDataSourceAliasList() {
        List<DataSourceExpression> dataSourceExpressionList = []
        dataSourceExpressionList << currentGinqExpression.fromExpression
        dataSourceExpressionList.addAll(currentGinqExpression.joinExpressionList)

        return dataSourceExpressionList.stream().map(e -> e.aliasExpr.text).collect(Collectors.toList())
    }

    private Tuple2<List<DeclarationExpression>, Expression> correctVariablesOfGinqExpression(DataSourceExpression dataSourceExpression, Expression expr) {
        String lambdaParamName = expr.getNodeMetaData(__LAMBDA_PARAM_NAME)
        if (null == lambdaParamName) {
            throw new GroovyBugError("lambdaParamName is null. dataSourceExpression:${dataSourceExpression}, expr:${expr}")
        }

        boolean isJoin = dataSourceExpression instanceof JoinExpression

        List<DeclarationExpression> declarationExpressionList
        if (isJoin) {
            def lambdaParam = new VariableExpression(lambdaParamName)
            Map<String, Expression> aliasToAccessPathMap = findAliasAccessPathForJoin(dataSourceExpression, lambdaParam)

            def variableNameSet = new HashSet<String>()
            expr.visit(new CodeVisitorSupport() {
                @Override
                void visitVariableExpression(VariableExpression expression) {
                    variableNameSet << expression.text
                    super.visitVariableExpression(expression)
                }
            })

            declarationExpressionList =
                    aliasToAccessPathMap.entrySet().stream()
                    .filter(e -> variableNameSet.contains(e.key))
                    .map(e -> {
                        def v = localVarX(e.key)
                        v.modifiers = v.modifiers | Opcodes.ACC_FINAL
                        return declX(v, e.value)
                    })
                    .collect(Collectors.toList())
        } else {
            declarationExpressionList = Collections.emptyList()
        }


        // (1) correct itself
        expr = correctVars(dataSourceExpression, lambdaParamName, expr)

        // (2) correct its children nodes
        // The synthetic lambda parameter `__t` represents the element from the result datasource of joining, e.g. `n1` innerJoin `n2`
        // The element from first datasource(`n1`) is referenced via `_t.v1`
        // and the element from second datasource(`n2`) is referenced via `_t.v2`
        expr = expr.transformExpression(new ExpressionTransformer() {
            @Override
            Expression transform(Expression expression) {
                Expression transformedExpression = correctVars(dataSourceExpression, lambdaParamName, expression)
                if (transformedExpression !== expression) {
                    return transformedExpression
                }

                return expression.transformExpression(this)
            }
        })

        return Tuple.tuple(declarationExpressionList, expr)
    }

    private Expression correctVars(DataSourceExpression dataSourceExpression, String lambdaParamName, Expression expression) {
        boolean groupByVisited = isGroupByVisited()

        Expression transformedExpression = null
        if (expression instanceof VariableExpression) {
            if (expression.isThisExpression()) return expression
            if (expression.text && Character.isUpperCase(expression.text.charAt(0))) return expression // type should not be transformed
            if (expression.text.startsWith(__META_DATA_MAP_NAME_PREFIX)) return expression

            if (groupByVisited) { //  groupby
                // in #1, we will correct receiver of built-in aggregate functions
                // the correct receiver is `__t.v2`, so we should not replace `__t` here
                if (lambdaParamName != expression.text) {
                    if (visitingAggregateFunction) {
                        if (FUNCTION_AGG == visitingAggregateFunction && _G == expression.text) {
                            transformedExpression =
                                    callX(
                                        new ClassExpression(QUERYABLE_HELPER_TYPE),
                                            "navigate",
                                        args(new VariableExpression(lambdaParamName), getMetaDataMethodCall(MD_ALIAS_NAME_LIST))
                                    )
                        } else {
                            transformedExpression = new VariableExpression(lambdaParamName)
                        }
                    } else {
                        if (groupNameListExpression.getExpressions().stream().map(e -> e.text).anyMatch(e -> e == expression.text)
                            || aliasNameListExpression.getExpressions().stream().map(e -> e.text).anyMatch(e -> e == expression.text)
                        ) {
                            // replace `gk` in the groupby with `__t.v1.gk`, note: __t.v1 stores the group key
                            transformedExpression = propX(propX(new VariableExpression(lambdaParamName), 'v1'), expression.text)
                        }
                    }
                }
            }
        } else if (expression instanceof MethodCallExpression) {
            // #1
            if (groupByVisited) { // groupby
                if (expression.implicitThis) {
                    String methodName = expression.methodAsString
                    visitingAggregateFunction = methodName
                    if (FUNCTION_COUNT == methodName && ((TupleExpression) expression.arguments).getExpressions().isEmpty()) { // Similar to count(*) in SQL
                        expression.objectExpression = propX(new VariableExpression(lambdaParamName), 'v2')
                        transformedExpression = expression
                    } else if (methodName in [FUNCTION_COUNT, FUNCTION_MIN, FUNCTION_MAX, FUNCTION_SUM, FUNCTION_AVG, FUNCTION_AGG] && 1 == ((TupleExpression) expression.arguments).getExpressions().size()) {
                        Expression lambdaCode = ((TupleExpression) expression.arguments).getExpression(0)
                        lambdaCode.putNodeMetaData(__LAMBDA_PARAM_NAME, findRootObjectExpression(lambdaCode).text)
                        transformedExpression =
                                callXWithLambda(
                                        propX(new VariableExpression(lambdaParamName), 'v2'), methodName,
                                        dataSourceExpression, lambdaCode)
                    }
                    visitingAggregateFunction = null
                }
            }
        }

        if (null != transformedExpression) {
            return transformedExpression
        }

        return expression
    }

    private static Map<String, Expression> findAliasAccessPathForJoin(DataSourceExpression dataSourceExpression, Expression prop) {
        boolean isJoin = dataSourceExpression instanceof JoinExpression
        if (!isJoin) return Collections.emptyMap()

        /*
                 * `n1`(`from` node) join `n2` join `n3`  will construct a join tree:
                 *
                 *  __t (join node)
                 *    |__ v2 (n3)
                 *    |__ v1 (join node)
                 *         |__ v2 (n2)
                 *         |__ v1 (n1) (`from` node)
                 *
                 * Note: `__t` is a tuple with 2 elements
                 * so  `n3`'s access path is `__t.v2`
                 * and `n2`'s access path is `__t.v1.v2`
                 * and `n1`'s access path is `__t.v1.v1`
                 *
                 * The following code shows how to construct the access path for variables
                 */
        Map<String, Expression> aliasToAccessPathMap = new LinkedHashMap<>()
        for (DataSourceExpression dse = dataSourceExpression; dse instanceof JoinExpression; dse = dse.dataSourceExpression) {
            DataSourceExpression otherDataSourceExpression = dse.dataSourceExpression
            Expression firstAliasExpr = otherDataSourceExpression?.aliasExpr ?: EmptyExpression.INSTANCE
            Expression secondAliasExpr = dse.aliasExpr

            aliasToAccessPathMap.put(secondAliasExpr.text, propX(prop, 'v2'))

            if (otherDataSourceExpression instanceof JoinExpression) {
                prop = propX(prop, 'v1')
            } else {
                aliasToAccessPathMap.put(firstAliasExpr.text, propX(prop, 'v1'))
            }
        }

        return aliasToAccessPathMap
    }

    private static Expression findRootObjectExpression(Expression expression) {
        if (expression instanceof PropertyExpression) {
            Expression expr = expression
            for (; expr instanceof PropertyExpression; expr = ((PropertyExpression) expr).objectExpression) {}
            return expr
        }

        return expression
    }

    private String visitingAggregateFunction

    @Override
    Expression visit(AbstractGinqExpression expression) {
        return expression.accept(this)
    }

    private MethodCallExpression callXWithLambda(Expression receiver, String methodName, DataSourceExpression dataSourceExpression, Expression lambdaCode) {
        LambdaExpression lambdaExpression = constructLambdaExpression(dataSourceExpression, lambdaCode)

        callXWithLambda(receiver, methodName, lambdaExpression)
    }

    private LambdaExpression constructLambdaExpression(DataSourceExpression dataSourceExpression, Expression lambdaCode) {
        Tuple3<String, List<DeclarationExpression>, Expression> paramNameAndLambdaCode = correctVariablesOfLambdaExpression(dataSourceExpression, lambdaCode)

        List<DeclarationExpression> declarationExpressionList = paramNameAndLambdaCode.v2
        List<Statement> statementList = []
        statementList.addAll(declarationExpressionList.stream().map(e -> stmt(e)).collect(Collectors.toList()))
        statementList.add(stmt(paramNameAndLambdaCode.v3))

        lambdaX(
                params(param(ClassHelper.DYNAMIC_TYPE, paramNameAndLambdaCode.v1)),
                block(statementList as Statement[])
        )
    }

    private int lambdaParamSeq = 0
    private String generateLambdaParamName() {
        "__t_${lambdaParamSeq++}"
    }

    private Tuple3<String, List<DeclarationExpression>, Expression> correctVariablesOfLambdaExpression(DataSourceExpression dataSourceExpression, Expression lambdaCode) {
        boolean groupByVisited = isGroupByVisited()

        List<DeclarationExpression> declarationExpressionList = Collections.emptyList()
        String lambdaParamName
        if (dataSourceExpression instanceof JoinExpression || groupByVisited) {
            lambdaParamName = lambdaCode.getNodeMetaData(__LAMBDA_PARAM_NAME)
            if (!lambdaParamName || visitingAggregateFunction) {
                lambdaParamName = generateLambdaParamName()
            }

            lambdaCode.putNodeMetaData(__LAMBDA_PARAM_NAME, lambdaParamName)
            Tuple2<List<DeclarationExpression>, Expression> declarationAndLambdaCode = correctVariablesOfGinqExpression(dataSourceExpression, lambdaCode)
            if (!(visitingAggregateFunction || (groupByVisited && visitingSelect))) {
                declarationExpressionList = declarationAndLambdaCode.v1
            }
            lambdaCode = declarationAndLambdaCode.v2
        } else {
            lambdaParamName = dataSourceExpression.aliasExpr.text
            lambdaCode.putNodeMetaData(__LAMBDA_PARAM_NAME, lambdaParamName)
        }

        if (lambdaCode instanceof ConstructorCallExpression) {
            if (NAMEDRECORD_CLASS_NAME == lambdaCode.type.redirect().name) {
                // store the source record
                lambdaCode = callX(lambdaCode, 'sourceRecord', new VariableExpression(lambdaParamName))
            }
        }

        return Tuple.tuple(lambdaParamName, declarationExpressionList, lambdaCode)
    }

    private boolean isGroupByVisited() {
        return currentGinqExpression.getNodeMetaData(__GROUPBY_VISITED) ?: false
    }

    private boolean isVisitingSelect() {
        return currentGinqExpression.getNodeMetaData(__VISITING_SELECT) ?: false
    }

    private boolean isRowNumberUsed() {
        return currentGinqExpression.getNodeMetaData(__RN_USED)  ?: false
    }

    private static MethodCallExpression callXWithLambda(Expression receiver, String methodName, LambdaExpression lambdaExpression) {
        callX(
                receiver,
                methodName,
                lambdaExpression
        )
    }

    private static ClassExpression makeQueryableCollectionClassExpression() {
        new ClassExpression(QUERYABLE_TYPE)
    }

    @Override
    SourceUnit getSourceUnit() {
        sourceUnit
    }

    private final SourceUnit sourceUnit
    private final Deque<GinqExpression> ginqExpressionStack = new ArrayDeque<>()

    private static final ClassNode MAPS_TYPE = makeWithoutCaching(Maps.class)
    private static final ClassNode QUERYABLE_TYPE = makeWithoutCaching(Queryable.class)
    private static final ClassNode ORDER_TYPE = makeWithoutCaching(Queryable.Order.class)
    private static final ClassNode NAMED_RECORD_TYPE = makeWithoutCaching(NamedRecord.class)
    private static final ClassNode QUERYABLE_HELPER_TYPE = makeWithoutCaching(QueryableHelper.class)

    private static final List<String> ORDER_OPTION_LIST = Arrays.asList('asc', 'desc')
    private static final String FUNCTION_COUNT = 'count'
    private static final String FUNCTION_MIN = 'min'
    private static final String FUNCTION_MAX = 'max'
    private static final String FUNCTION_SUM = 'sum'
    private static final String FUNCTION_AVG = 'avg'
    private static final String FUNCTION_AGG = 'agg'

    private static final String NAMEDRECORD_CLASS_NAME = NamedRecord.class.name

    private static final String __METHOD_CALL_RECEIVER = "__METHOD_CALL_RECEIVER"
    private static final String __GROUPBY_VISITED = "__GROUPBY_VISITED"
    private static final String __VISITING_SELECT = "__VISITING_SELECT"
    private static final String __LAMBDA_PARAM_NAME = "__LAMBDA_PARAM_NAME"
    private static final String  __RN_USED = '__RN_USED'
    private static final String __META_DATA_MAP_NAME_PREFIX = '__metaDataMap_'
    private static final String __ROW_NUMBER_NAME_PREFIX = '__rowNumber_'
    private static final String MD_GROUP_NAME_LIST = "groupNameList"
    private static final String MD_SELECT_NAME_LIST = "selectNameList"
    private static final String MD_ALIAS_NAME_LIST = 'aliasNameList'
    private static final String _G = '_g' // the implicit variable representing grouped `Queryable` object
    private static final String _RN = '_rn' // the implicit variable representing row number
}
