package jobshopmgt

import org.apache.ofbiz.base.util.UtilDateTime

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */


import org.apache.ofbiz.entity.GenericValue
import org.apache.ofbiz.manufacturing.jobshopmgt.ProductionRun
import org.apache.velocity.util.introspection.UberspectImpl

productionRunId = parameters.productionRunId ?: parameters.workEffortId
//projectId = parameters.projectId
//userLogin = parameters.userLogin

ganttList = new LinkedList()

def getRootPR(prId) {
    if (!prId) return null;

    successor = from("WorkEffortAssoc").where("workEffortIdFrom", prId, "workEffortAssocTypeId", "WORK_EFF_PRECEDENCY").queryOne()

    if (!successor)
        return prId;

    return getRootPR(successor.workEffortIdTo)
}

def traverse(prId) {
    if (!prId) return;

    predecessors = from("WorkEffortAssoc").where("workEffortIdTo", prId, "workEffortAssocTypeId", "WORK_EFF_PRECEDENCY").queryList()

    String precedingPR = null
    precedingPRDueDate = null
    if (predecessors) {
        String s = ""
        predecessors.each { predecessor ->
            s += predecessor.workEffortIdFrom + ", "
            d = traverse(predecessor.workEffortIdFrom)
            if (precedingPRDueDate == null || precedingPRDueDate < d)
                precedingPRDueDate = d
        }
        s = s.substring(0, s.lastIndexOf(","))
        if (s.length() > 0)
            precedingPR = s
    }

    return execute(prId, precedingPR, precedingPRDueDate)
}

def shift(start, end, precedingDueDate, boolean shiftStart) {
    offset = 2
    if (precedingDueDate)
        offset = UtilDateTime.getIntervalInDays(start, precedingDueDate) + 2

    if (shiftStart) {
        return UtilDateTime.addDaysToTimestamp(start, offset)
    } else {
        return UtilDateTime.addDaysToTimestamp(end, offset)
    }

}

def execute(prId, precedingPR, precedingPRDueDate) {
    if (!prId) return;

    List<String> fList = Arrays.asList("workEffortId", "workEffortTypeId", "sequenceNum", "workEffortName",
            "workEffortParentId", "estimatedStartDate", "estimatedCompletionDate",
            "actualStartDate", "actualCompletionDate");

    ProductionRun productionRun = new ProductionRun(prId, delegator, dispatcher)
    Map prMap = productionRun.genericValue.getFields(fList)

    prMap.predecessor = precedingPR
    prMap.url = "/manufacturing/control/ProductionRunTasks?productionRunId=" + prMap.workEffortId
    prMap.estimatedStartDate = UtilDateTime.toDateString(prMap.estimatedStartDate, "MM/dd/yyyy")
    prMap.estimatedCompletionDate = UtilDateTime.toDateString(prMap.estimatedCompletionDate, "MM/dd/yyyy")
    ganttList.add(prMap)

    latestDueDate = null
    //String precedingTask = null

    productionRunRoutingTasks = productionRun.getProductionRunRoutingTasks()
    productionRunRoutingTasks.each { productionRunRoutingTask ->
        Map taskMap = productionRunRoutingTask.getFields(fList)

        taskMap.url = "/manufacturing/control/ProductionRunTasks?routingTaskId=" + taskMap.workEffortId +
                "&productionRunId=" + prMap.workEffortId

        if (!taskMap.estimatedStartDate && taskMap.actualStartDate) {
            taskMap.estimatedStartDate = taskMap.actualStartDate
        }
        if (!taskMap.estimatedStartDate) {
            taskMap.estimatedStartDate = prMap.estimatedStartDate
        }
        if (!taskMap.estimatedCompletionDate && taskMap.actualCompletionDate) {
            taskMap.estimatedCompletionDate = taskMap.actualCompletionDate
        }
        if (!taskMap.estimatedCompletionDate ||
                UtilDateTime.getIntervalInDays(taskMap.estimatedStartDate, taskMap.estimatedCompletionDate) < 2)
            taskMap.estimatedCompletionDate = UtilDateTime.addDaysToTimestamp(taskMap.estimatedStartDate, 3)

        startD = taskMap.estimatedStartDate
        endD = taskMap.estimatedCompletionDate
        taskMap.estimatedStartDate = UtilDateTime.toDateString(
                shift(startD, endD, precedingPRDueDate, true),
                "MM/dd/yyyy")

        d = shift(startD, endD, precedingPRDueDate, false);
        if (latestDueDate == null || latestDueDate < d)
            latestDueDate = d
        taskMap.estimatedCompletionDate = UtilDateTime.toDateString(d, "MM/dd/yyyy")

        //if (precedingTask)
            //taskMap.predecessor = precedingTask

        ganttList.add(taskMap)

        //precedingTask = taskMap.workEffortId
    }

    return latestDueDate;
}



traverse(getRootPR(productionRunId))



/**************
//project info
result = runService('getProject', [projectId : projectId, userLogin : userLogin])
project = result.projectInfo
if (project && project.startDate)
    context.chartStart = project.startDate
else
    context.chartStart = UtilDateTime.nowTimestamp() // default todays date
if (project && project.completionDate)
    context.chartEnd = project.completionDate
else
    context.chartEnd = UtilDateTime.addDaysToTimestamp(UtilDateTime.nowTimestamp(), 14) // default 14 days long

if (project == null) return

//ganttList = new LinkedList()
result = runService('getProjectPhaseList', [userLogin : userLogin , projectId : projectId])
phases = result.phaseList
if (phases) {
    phases.each { phase ->
        newPhase = phase
        newPhase.phaseNr = phase.phaseId
        if (!newPhase.estimatedStartDate && newPhase.actualStartDate) {
            newPhase.estimatedStartDate = newPhase.actualStartDate
        }
        if (!newPhase.estimatedStartDate) {
            newPhase.estimatedStartDate = context.chartStart
        }
        if (!newPhase.estimatedCompletionDate && newPhase.actualCompletionDate) {
            newPhase.estimatedCompletionDate = newPhase.actualCompletionDateDate
        }
        if (!newPhase.estimatedCompletionDate) {
            newPhase.estimatedCompletionDate = UtilDateTime.addDaysToTimestamp(newPhase.estimatedStartDate, 3)
        }
        newPhase.workEffortTypeId = "PHASE"
        ganttList.add(newPhase)
        cond = EntityCondition.makeCondition(
                [
                EntityCondition.makeCondition("currentStatusId", EntityOperator.NOT_EQUAL, "PTS_CANCELLED"),
                EntityCondition.makeCondition("workEffortParentId", EntityOperator.EQUALS, phase.phaseId)
                ], EntityOperator.AND)
        tasks = from("WorkEffort").where(cond).orderBy("sequenceNum","workEffortName").queryList()
        if (tasks) {
            tasks.each { task ->
                resultTaskInfo = runService('getProjectTask', [userLogin : userLogin , taskId : task.workEffortId])
                taskInfo = resultTaskInfo.taskInfo
                taskInfo.taskNr = task.workEffortId
                taskInfo.phaseNr = phase.phaseId

                if (taskInfo.plannedHours && !"PTS_COMPLETED".equals(taskInfo.currentStatusId) && taskInfo.plannedHours > taskInfo.actualHours) {
                    taskInfo.resource = taskInfo.plannedHours //+ " Hrs"
                } else {
                    taskInfo.resource = taskInfo.actualHours //+ " Hrs"
                }
                if (taskInfo.resource)
                    taskInfo.resource += " Hrs"

                //String resource = from("WorkEffortPartyAssignment").where("workEffortId", task.workEffortId).cache().queryFirst().partyId
                String resource = null
                assignments = from("WorkEffortPartyAssignment").where("workEffortId", task.workEffortId).queryList()
                if (assignments) {
                    resource = ""
                    assignments.each { assignment ->
                        person = from("Person").where("partyId", assignment.partyId).cache().queryFirst()
                        if (person)
                            resource += person.firstName + " " + person.lastName + ", "
                    }
                }
                if (taskInfo.resource && resource)
                    taskInfo.resource += " | " + resource.substring(0, resource.lastIndexOf(","))
                if (!taskInfo.resource && resource)
                    taskInfo.resource = resource.substring(0, resource.lastIndexOf(","))

                Double duration = resultTaskInfo.plannedHours
                if ("PTS_COMPLETED".equals(taskInfo.currentStatusId)) {
                    taskInfo.completion = 100
                } else {
                    if (taskInfo.actualHours && taskInfo.plannedHours) {
                        taskInfo.completion = new BigDecimal(taskInfo.actualHours * 100 / taskInfo.plannedHours).setScale(0, BigDecimal.ROUND_UP)
                    } else {
                        taskInfo.completion = 0
                    }
                }
                if (!taskInfo.estimatedStartDate && taskInfo.actualStartDate) {
                    taskInfo.estimatedStartDate = taskInfo.actualStartDate
                }
                if (!taskInfo.estimatedStartDate) {
                    taskInfo.estimatedStartDate = newPhase.estimatedStartDate
                }
                if (!taskInfo.estimatedCompletionDate && taskInfo.actualCompletionDate) {
                    taskInfo.estimatedCompletionDate = taskInfo.actualCompletionDate
                }
                if (!taskInfo.estimatedCompletionDate && !duration) {
                    taskInfo.estimatedCompletionDate = UtilDateTime.addDaysToTimestamp(newPhase.estimatedStartDate, 3)
                } else if (!taskInfo.estimatedCompletionDate && duration) {
                    taskInfo.estimatedCompletionDate = UtilDateTime.addDaysToTimestamp(newPhase.estimatedStartDate, duration/8)
                }
                taskInfo.estimatedStartDate = UtilDateTime.toDateString(taskInfo.estimatedStartDate, "MM/dd/yyyy")
                taskInfo.estimatedCompletionDate = UtilDateTime.toDateString(taskInfo.estimatedCompletionDate, "MM/dd/yyyy")
                taskInfo.workEffortTypeId = task.workEffortTypeId
                if (security.hasEntityPermission("PROJECTMGR", "_READ", session) || security.hasEntityPermission("PROJECTMGR", "_ADMIN", session)) {
                    taskInfo.url = "/projectmgr/control/taskView?workEffortId="+task.workEffortId
                } else {
                    taskInfo.url = ""
                }

                // dependency can only show one in the ganttchart, so onl show the latest one..
                preTasks = from("WorkEffortAssoc").where("workEffortIdTo", task.workEffortId).orderBy("workEffortIdFrom").queryList()
                latestTaskIds = new LinkedList()
                preTasks.each { preTask ->
                    wf = preTask.getRelatedOne("FromWorkEffort", false)
                    latestTaskIds.add(wf.workEffortId)
                }
                count = 0
                if (latestTaskIds) {
                    taskInfo.preDecessor = ""
                    for (i in latestTaskIds) {
                        if (count > 0) {
                            taskInfo.preDecessor = taskInfo.preDecessor +", " + i
                        } else {
                            taskInfo.preDecessor = taskInfo.preDecessor + i
                        }
                        count ++
                    }
                }
                ganttList.add(taskInfo)
            }
        }
    }
}
********/

context.phaseTaskList = ganttList
