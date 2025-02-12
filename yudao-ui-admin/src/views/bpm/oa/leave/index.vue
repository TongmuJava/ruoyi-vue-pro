<template>
  <div class="app-container">

    <!-- 搜索工作栏 -->
    <el-form :model="queryParams" ref="queryForm" :inline="true" v-show="showSearch" label-width="68px">
      <el-form-item label="请假类型" prop="type">
        <el-select v-model="queryParams.type" placeholder="请选择请假类型" clearable>
          <el-option v-for="dict in leaveTypeDictData" :key="dict.value" :label="dict.label" :value="dict.value" />
        </el-select>
      </el-form-item>
      <el-form-item label="申请时间">
        <el-date-picker v-model="dateRangeCreateTime" size="small" style="width: 240px" value-format="yyyy-MM-dd"
                        type="daterange" range-separator="-" start-placeholder="开始日期" end-placeholder="结束日期" />
      </el-form-item>
      <el-form-item label="结果" prop="result">
        <el-select v-model="queryParams.result" placeholder="请选择流结果" clearable size="small">
          <el-option v-for="dict in this.getDictDatas(DICT_TYPE.BPM_PROCESS_INSTANCE_RESULT)"
                     :key="dict.value" :label="dict.label" :value="dict.value"/>
        </el-select>
      </el-form-item>
      <el-form-item label="原因" prop="reason">
        <el-input v-model="queryParams.reason" placeholder="请输入原因" clearable size="small" @keyup.enter.native="handleQuery"/>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="el-icon-search" size="mini" @click="handleQuery">搜索</el-button>
        <el-button icon="el-icon-refresh" size="mini" @click="resetQuery">重置</el-button>
      </el-form-item>
    </el-form>

    <!-- 操作工具栏 -->
    <el-row :gutter="10" class="mb8">
      <el-col :span="1.5">
        <el-button type="primary" icon="el-icon-plus" size="mini" v-hasPermi="['bpm:oa-leave:create']" @click="handleAdd">发起请假</el-button>
      </el-col>
      <right-toolbar :showSearch.sync="showSearch" @queryTable="getList"></right-toolbar>
    </el-row>

    <!-- 列表 -->
    <el-table v-loading="loading" :data="list">
      <el-table-column label="申请编号" align="center" prop="id" />
      <el-table-column label="状态" align="center" prop="result" :formatter="resultFormat" />
      <el-table-column label="开始时间" align="center" prop="startTime" width="180">
        <template slot-scope="scope">
          <span>{{ parseTime(scope.row.startTime) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="结束时间" align="center" prop="endTime" width="180">
        <template slot-scope="scope">
          <span>{{ parseTime(scope.row.endTime) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="请假类型" align="center" prop="type" :formatter="typeFormat" />
      <el-table-column label="原因" align="center" prop="reason" />
      <el-table-column label="申请时间" align="center" prop="applyTime" width="180">
        <template slot-scope="scope">
          <span>{{ parseTime(scope.row.createTime) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" align="center" class-name="small-padding fixed-width" width="200">
        <template slot-scope="scope">
          <el-button size="mini" type="text" icon="el-icon-delete" @click="handleCancel(scope.row)"
                     v-hasPermi="['bpm:oa-leave:create']" v-if="scope.row.result === 1">取消请假</el-button>
          <el-button size="mini" type="text" icon="el-icon-view" @click="handleDetail(scope.row)"
                     v-hasPermi="['bpm:oa-leave:query']">详情</el-button>
          <el-button size="mini" type="text" icon="el-icon-edit" @click="handleProcessDetail(scope.row)">审批进度</el-button>
        </template>
      </el-table-column>
    </el-table>
    <!-- 分页组件 -->
    <pagination v-show="total > 0" :total="total" :page.sync="queryParams.pageNo" :limit.sync="queryParams.pageSize"
                @pagination="getList"/>

  </div>
</template>

<script>
import { getLeavePage } from "@/api/bpm/leave"
import { getDictDataLabel, getDictDatas, DICT_TYPE } from '@/utils/dict'
import {cancelProcessInstance} from "@/api/bpm/processInstance";

export default {
  name: "Leave",
  components: {
  },
  data() {
    return {
      // 遮罩层
      loading: true,
      // 显示搜索条件
      showSearch: true,
      // 总条数
      total: 0,
      // 请假申请列表
      list: [],
      //审批进度弹出层
      dateRangeCreateTime: [],
      // 查询参数
      queryParams: {
        pageNo: 1,
        pageSize: 10,
        result: null,
        type: null,
        reason: null,
      },

      leaveTypeDictData: getDictDatas(DICT_TYPE.BPM_OA_LEAVE_TYPE),
      leaveResultData: getDictDatas(DICT_TYPE.BPM_PROCESS_INSTANCE_RESULT),
    };
  },
  created() {
    this.getList();
  },
  methods: {
    /** 查询列表 */
    getList() {
      this.loading = true;
      // 处理查询参数
      let params = {...this.queryParams};
      this.addBeginAndEndTime(params, this.dateRangeCreateTime, 'createTime');
      // 执行查询
      getLeavePage(params).then(response => {
        this.list = response.data.list;
        this.total = response.data.total;
        this.loading = false;
      });
    },
    /** 搜索按钮操作 */
    handleQuery() {
      this.queryParams.pageNo = 1;
      this.getList();
    },
    /** 重置按钮操作 */
    resetQuery() {
      this.dateRangeCreateTime = [];
      this.resetForm("queryForm");
      this.handleQuery();
    },
    /** 新增按钮操作 */
    handleAdd() {
      this.$router.push({ path: "/bpm/oa/leave/create"});
    },
    /** 详情按钮操作 */
    handleDetail(row) {
      this.$router.push({ path: "/bpm/oa/leave/detail", query: { id: row.id}});
    },
    /** 查看审批进度的操作 */
    handleProcessDetail(row) {
      this.$router.push({ path: "/bpm/process-instance/detail", query: { id: row.processInstanceId}});
    },
    /** 取消请假 */
    handleCancel(row) {
      const id = row.processInstanceId;
      this.$prompt('请输入取消原因？', "取消流程", {
        type: 'warning',
        confirmButtonText: "确定",
        cancelButtonText: "取消",
        inputPattern: /^[\s\S]*.*[^\s][\s\S]*$/, // 判断非空，且非空格
        inputErrorMessage: "取消原因不能为空",
      }).then(({ value }) => {
        return cancelProcessInstance(id, value);
      }).then(() => {
        this.getList();
        this.msgSuccess("取消成功");
      })
    },
    resultFormat(row, column) {
      return getDictDataLabel(DICT_TYPE.BPM_PROCESS_INSTANCE_RESULT, row.result)
    },
    typeFormat(row, column) {
      return getDictDataLabel(DICT_TYPE.BPM_OA_LEAVE_TYPE, row.type)
    },
  }
};
</script>
