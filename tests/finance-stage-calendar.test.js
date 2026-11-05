"use strict";
const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");
const html = fs.readFileSync("app/src/main/assets/index.html","utf8");
const code = html.slice(html.indexOf('let fwFinanceProjectId ='),html.indexOf('let fwReportProjectId='));
assert.ok(code.includes('const si=fwFinanceSelectedStage,stage=stages[si];'));
assert.ok(!code.includes('stages.forEach(function(stage,si)'));
assert.ok(code.includes('data-fw-calendar-date'));
assert.ok(code.includes('fwFinanceDrawCalendar(wrap.querySelector(".fw-finance-form"))'));
assert.match(html,/\.fw-finance-form input\[name="amount"\]\{width:min\(100%,148px\)\}/);
assert.match(html,/\.fw-finance-form input\[name="date"\]\{width:min\(100%,176px\)\}/);
const saved = {name:"Example Farm", selectedStage:1,budgetItems:[{text:"Site clearing",amount:7000,stage:0,date:"2026-03-25"},{text:"Prep",amount:200,stage:1,date:"2026-05-03"}],expenses:[{text:"Pump",amount:80,stage:2}]};
const elements = {fwFinanceScreen:{hidden:true},fwFinanceProjectName:{textContent:""},fwFinanceSummary:{innerHTML:""},fwFinanceLedger:{innerHTML:"",querySelector(){return null}}};
const listeners={};
const document={
  getElementById(id){assert.ok(elements[id],id);return elements[id];},
  querySelectorAll(){return [];},
  addEventListener(k,fn){listeners[k]=fn;}
};
const context=vm.createContext({
  document,window:{},Date,Intl,Number,String,Math,Array,
  getProjectCardRecord(){return saved;},
  projectCardStageNames(){return ["Discovery & Site visit","Initiation","In Progress","Handing Over","Completed"];},
  escapeProjectText(s){return String(s).replaceAll("&","&amp;").replaceAll("<","&lt;");},
  saveProjectCardRecord(){return true;},
  renderMiniRenovationDemo(){},
  renderCreatedProjects(){},
  confirm(){return true;},
  alert(){throw new Error("Unexpected alert")}
});
vm.runInContext(code+'\nthis.testFinance={open:fwFinanceOpen,render:fwFinanceRender,totals:fwFinanceTotals,setTab(v){fwFinanceTab=v;},setEdit(v){fwFinanceEditing=v;},setSelected(v){fwFinanceSelectedStage=v;},calendar:fwFinanceDrawCalendar};',context,{timeout:2000});
const f=context.testFinance;
f.open("project-a");
assert.equal(elements.fwFinanceScreen.hidden,false);
assert.match(elements.fwFinanceLedger.innerHTML,/id="fwFinanceStageSelect"/);
assert.match(elements.fwFinanceLedger.innerHTML,/2\. Initiation/);
assert.match(elements.fwFinanceLedger.innerHTML,/Prep/);
assert.doesNotMatch(elements.fwFinanceLedger.innerHTML,/Site clearing/);
assert.match(elements.fwFinanceSummary.innerHTML,/7,200\.00/);
assert.match(elements.fwFinanceSummary.innerHTML,/80\.00/);
const picker={id:"fwFinanceStageSelect",value:"0"};
listeners.change({target:picker});
assert.match(elements.fwFinanceLedger.innerHTML,/Site clearing/);
assert.doesNotMatch(elements.fwFinanceLedger.innerHTML,/>Prep</);
assert.match(elements.fwFinanceLedger.innerHTML,/7,000\.00/);
f.setTab("expenses");f.setSelected(2);f.render();
assert.match(elements.fwFinanceLedger.innerHTML,/>Pump</);
assert.match(elements.fwFinanceLedger.innerHTML,/80\.00/);
const panel={innerHTML:""};
const form={elements:{date:{value:"2026-05-03"}},querySelector(){return panel}};
f.setEdit({stage:1,index:1,calendarMonth:"2026-05"});
f.calendar(form);
assert.match(panel.innerHTML,/May 2026/);
assert.match(panel.innerHTML,/data-fw-calendar-date="2026-05-03"/);
assert.match(panel.innerHTML,/aria-pressed="true"/);
assert.match(panel.innerHTML,/data-fw-calendar-nav="-1"/);
assert.match(panel.innerHTML,/data-fw-calendar-nav="1"/);
console.log("Finance stage selection, totals, tab isolation and inline date calendar: PASS");
