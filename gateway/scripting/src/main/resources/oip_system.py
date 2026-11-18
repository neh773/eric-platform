# Gateway scripting prelude. Loaded into every GraalPy context before user code.
# Names follow the public system.* pages:

import fnmatch
import json
import sys
import types

_result = None


class _Date:
    def __init__(self, millis):
        self._millis = int(millis)

    def __repr__(self):
        return "Date(%d)" % self._millis

    def __eq__(self, other):
        return isinstance(other, _Date) and self._millis == other._millis

    def __hash__(self):
        return hash(self._millis)


def _millis(value):
    if isinstance(value, _Date):
        return value._millis
    if isinstance(value, bool) or value is None:
        raise TypeError("expected a date")
    if isinstance(value, (int, float)):
        return int(value)
    raise TypeError("expected a date")


def _is_dataset(value):
    columns = value.get("columns")
    rows = value.get("rows")
    if not isinstance(columns, list) or not isinstance(rows, list):
        return False
    if any(not isinstance(row, list) for row in rows):
        return False
    return all(
        isinstance(col, dict) and isinstance(col.get("name"), str) and isinstance(col.get("type"), str)
        for col in columns
    )


def _revive(value):
    if isinstance(value, dict) and _is_dataset(value):
        try:
            return _Dataset(value["columns"], value["rows"])
        except ValueError:
            return value
    return value


class _QV:
    def __init__(self, raw):
        self.value = _revive(raw.get("value"))
        self.quality = raw.get("quality")
        self.timestamp = _Date(raw.get("timestamp") or 0)

    def getValue(self):
        return self.value

    def getQuality(self):
        return self.quality

    def getTimestamp(self):
        return self.timestamp


def _qv(raw):
    return _QV(raw)


class _Path:
    def __init__(self, text):
        self._text = "" if text is None else str(text)

    def __str__(self):
        return self._text

    def __repr__(self):
        return self._text

    def getItemName(self):
        body = self._text.split("]", 1)[-1]
        if body == "":
            return ""
        return body.split("/")[-1]

    def getParentPath(self):
        body = self._text.split("]", 1)[-1]
        head = self._text[: len(self._text) - len(body)]
        if "/" not in body:
            return head
        return head + body.rsplit("/", 1)[0]


class _AlarmEvent:
    """The alarm event a tag event script receives as event.alarm: attributes, plus get() and dict-style reads."""
    def __init__(self, raw):
        self._raw = raw
        for key, value in raw.items():
            setattr(self, key, value)

    def get(self, name, default=None):
        return self._raw.get(name, default)

    def __getitem__(self, name):
        return self._raw[name]

    def __contains__(self, name):
        return name in self._raw

    def toDict(self):
        return dict(self._raw)


class _Event:
    def __init__(self, raw):
        self._raw = raw
        self.tagPath = _Path(raw.get("tagPath"))
        self.changes = list(raw.get("changes") or [])
        self.alarmName = raw.get("alarmName") or ""
        self.trigger = raw.get("trigger") or ""
        alarm = raw.get("alarm")
        self.alarm = None if alarm is None else _AlarmEvent(alarm)

    def getTagPath(self):
        return self.tagPath

    def getCurrentValue(self):
        return _qv(self._raw["newValue"])

    def getValue(self):
        return self.getCurrentValue()

    def getPreviousValue(self):
        prev = self._raw.get("previousValue")
        if prev is None:
            return None
        return _qv(prev)


class _Dataset:
    def __init__(self, columns, rows):
        self._cols = []
        self._types = []
        for column in columns:
            if isinstance(column, dict):
                self._cols.append(str(column.get("name")))
                kind = column.get("type")
                self._types.append(None if kind is None else str(kind))
            else:
                self._cols.append(str(column))
                self._types.append(None)
        self._rows = [list(r) for r in rows]
        width = len(self._cols)
        for row in self._rows:
            if len(row) != width:
                raise ValueError("row length does not match the column headers")

    def getRowCount(self):
        return len(self._rows)

    def getColumnCount(self):
        return len(self._cols)

    def getColumnNames(self):
        return list(self._cols)

    def getColumnName(self, index):
        return self._cols[int(index)]

    def getColumnType(self, col):
        index = self.getColumnIndex(col) if isinstance(col, str) else int(col)
        return self._types[index]

    def getColumnIndex(self, name):
        return self._cols.index(str(name))

    def getValueAt(self, row, col):
        index = self.getColumnIndex(col) if isinstance(col, str) else int(col)
        return self._rows[int(row)][index]

    def getColumnAsList(self, col):
        index = self.getColumnIndex(col) if isinstance(col, str) else int(col)
        return [r[index] for r in self._rows]

    def __len__(self):
        return len(self._rows)

    def __iter__(self):
        for i in range(len(self._rows)):
            yield _Row(self, i)


class _Row:
    def __init__(self, dataset, index):
        self._ds = dataset
        self._i = index

    def __getitem__(self, key):
        return self._ds.getValueAt(self._i, key)


def _json_default(obj):
    if isinstance(obj, _Dataset):
        columns = []
        for index, name in enumerate(obj._cols):
            kind = obj._types[index]
            if kind is None:
                columns.append({"name": name})
            else:
                columns.append({"name": name, "type": kind})
        return {"columns": columns, "rows": obj._rows}
    if isinstance(obj, _Date):
        return obj._millis
    raise TypeError("cannot encode %s" % type(obj).__name__)


class _PyDataSet:
    def __init__(self, dataset):
        self._ds = dataset

    def __iter__(self):
        return iter(self._ds)

    def __len__(self):
        return len(self._ds)

    def getRowCount(self):
        return self._ds.getRowCount()


class _BrowseResult:
    def __init__(self, raw):
        self.name = raw.get("name")
        self.fullPath = raw.get("fullPath")
        self.tagType = raw.get("tagType")
        self.dataType = raw.get("dataType")
        self.hasChildren = bool(raw.get("hasChildren"))


class _Browse:
    def __init__(self, results):
        self.results = results

    def getResults(self):
        return self.results


class _Logger:
    def __init__(self, name, host):
        self.name = name
        self._h = host

    def _emit(self, level, message):
        text = "" if message is None else str(message)
        self._h.log(level, self.name, text)

    def _fmt(self, level, fmt, args):
        self._h.log(level, self.name, self._h.formatMessage(str(fmt), json.dumps(list(args))))


def _bind_logger_methods():
    levels = (
        ("TRACE", "trace", "isTraceEnabled"),
        ("DEBUG", "debug", "isDebugEnabled"),
        ("INFO", "info", "isInfoEnabled"),
        ("WARN", "warn", "isWarnEnabled"),
        ("ERROR", "error", "isErrorEnabled"),
        ("FATAL", "fatal", "isFatalEnabled"),
    )
    for level, name, enabled_name in levels:
        def plain(self, message, _level=level):
            self._emit(_level, message)

        def formatted(self, fmt, *args, _level=level):
            self._fmt(_level, fmt, args)

        def enabled(self, _level=level):
            return bool(self._h.logEnabled(self.name, _level))

        setattr(_Logger, name, plain)
        setattr(_Logger, name + "f", formatted)
        setattr(_Logger, enabled_name, enabled)


_bind_logger_methods()


def _install_system(host):
    def readBlocking(paths, timeout=45000):
        del timeout
        if isinstance(paths, str):
            paths = [paths]
        raw = json.loads(host.tagRead(json.dumps(list(paths))))
        return [_QV(item) for item in raw]

    def writeBlocking(paths, values, timeout=45000):
        del timeout
        if isinstance(paths, str):
            paths = [paths]
        if not isinstance(values, (list, tuple)):
            values = [values]
        raw = json.loads(host.tagWrite(json.dumps(list(paths)), json.dumps(list(values), default=_json_default)))
        return list(raw)

    def _walk(path, depth, acc):
        page = json.loads(host.tagBrowse(path or ""))
        for item in page.get("results") or []:
            acc.append(item)
            if item.get("hasChildren") and depth > 0:
                _walk(item.get("fullPath") or "", depth - 1, acc)

    def browse(path="", filter=None):
        recursive = bool(filter.get("recursive")) if isinstance(filter, dict) else False
        found = []
        if recursive:
            _walk(path or "", 6, found)
        else:
            page = json.loads(host.tagBrowse(path or ""))
            found.extend(page.get("results") or [])
        if isinstance(filter, dict):
            name = filter.get("name")
            tag_type = filter.get("tagType")
            if name:
                found = [item for item in found if fnmatch.fnmatch(str(item.get("name")), str(name))]
            if tag_type:
                found = [item for item in found if item.get("tagType") == tag_type]
        return _Browse([_BrowseResult(item) for item in found])

    def _add(unit):
        def fn(date, amount, _unit=unit):
            return _Date(host.addDate(_millis(date), _unit, int(amount)))

        return fn

    def _field(field):
        def fn(date, _field=field):
            return int(host.dateField(_millis(date), _field))

        return fn

    def _span(unit):
        def fn(start, end, _unit=unit):
            return int(host.between(_millis(start), _millis(end), _unit))

        return fn

    def now():
        return _Date(host.nowMillis())

    def format(date, pattern):
        return host.formatDate(_millis(date), str(pattern))

    def parse(text, pattern):
        return _Date(host.parseDate(str(text), str(pattern)))

    def fromMillis(millis):
        return _Date(int(millis))

    def getDate(year, month, day):
        return _Date(host.makeDate(int(year), int(month), int(day)))

    def midnight(date):
        return _Date(host.midnight(_millis(date)))

    def isAfter(date, other):
        return _millis(date) > _millis(other)

    def isBefore(date, other):
        return _millis(date) < _millis(other)

    def isBetween(date, start, end):
        value = _millis(date)
        return _millis(start) <= value <= _millis(end)

    def getTimezone():
        return host.zoneId()

    def getTimezoneOffset(date=None):
        millis = host.nowMillis() if date is None else _millis(date)
        return int(host.zoneOffset(millis))

    def getTimezoneRawOffset():
        return int(host.zoneRawOffset())

    def toDataset(headers, data):
        return _Dataset(headers, data)

    def toPyDataSet(dataset):
        return _PyDataSet(dataset)

    def filterColumns(dataset, columns):
        indexes = [dataset.getColumnIndex(col) for col in columns]
        headers = []
        for index, name in zip(indexes, columns):
            kind = dataset.getColumnType(index)
            headers.append(str(name) if kind is None else {"name": str(name), "type": kind})
        rows = []
        for row in range(dataset.getRowCount()):
            rows.append([dataset.getValueAt(row, index) for index in indexes])
        return _Dataset(headers, rows)

    def getLogger(name):
        return _Logger(str(name), host)

    def jsonEncode(value):
        return json.dumps(value)

    def jsonDecode(text):
        return json.loads(text)

    def invokeLater(script, delayMillis=0):
        host.schedule(int(delayMillis), str(script))

    def getGatewayStatus():
        return json.loads(host.statusJson())

    # system.eam (SCR-012): controller-side agent status and tasks.
    def _eam(function, **args):
        clean = {}
        for key, value in args.items():
            if value is None:
                continue
            if isinstance(value, _Date):
                value = value._millis
            if isinstance(value, (list, tuple)):
                value = [str(v) for v in value]
            clean[key] = value
        return json.loads(host.eamCall(function, json.dumps(clean)))["value"]

    def _eam_dataset(raw):
        return _Dataset(raw.get("columns") or [], raw.get("rows") or [])

    def eamGetGroups():
        return _eam("getGroups")

    def eamQueryAgentStatus(groupIds=None, agentIds=None, isConnected=None):
        return _eam_dataset(_eam("queryAgentStatus", groupIds=groupIds, agentIds=agentIds, isConnected=isConnected))

    def eamQueryAgentHistory(groupIds=None, agentIds=None, startDate=None, endDate=None, limit=100):
        return _eam_dataset(_eam("queryAgentHistory", groupIds=groupIds, agentIds=agentIds,
                                 startDate=startDate, endDate=endDate, limit=limit))

    def eamRunTask(taskName):
        return _eam("runTask", taskName=str(taskName))

    def _decode(raw):
        kind = raw.get("kind")
        if kind == "rows":
            return _Dataset(raw.get("columns") or [], raw.get("rows") or [])
        if kind == "scalar":
            return raw.get("value")
        if kind == "update":
            return raw.get("count")
        raise RuntimeError("unknown query result")

    def execQuery(path, parameters=None, tx=None, project=None):
        if isinstance(parameters, str) and tx is None and project is None:
            project = parameters
            parameters = None
        if project not in (None, "", "gateway"):
            raise RuntimeError("unknown project %r" % (project,))
        if parameters is None:
            parameters = {}
        if not isinstance(parameters, dict):
            raise TypeError("parameters must be a dictionary")
        return _decode(json.loads(host.execQueryTx(str(path), json.dumps(parameters), "" if tx in (None, "") else str(tx))))

    def _db(database, tx):
        if database in (None, "") and tx in (None, ""):
            raise TypeError("a database connection is required")
        return "" if database in (None, "") else str(database), "" if tx in (None, "") else str(tx)

    def runPrepQuery(query, params=None, database=None, tx=None):
        database, tx = _db(database, tx)
        if params is None:
            params = {}
        return _decode(json.loads(host.scriptQuery(database, str(query), json.dumps(params), "true", "rows", tx)))

    def runQuery(query, database=None, tx=None):
        database, tx = _db(database, tx)
        return _decode(json.loads(host.scriptQuery(database, str(query), "", "false", "rows", tx)))

    def runPrepUpdate(query, params=None, database=None, tx=None):
        database, tx = _db(database, tx)
        if params is None:
            params = {}
        return _decode(json.loads(host.scriptQuery(database, str(query), json.dumps(params), "true", "update", tx)))

    def runUpdateQuery(query, database=None, tx=None):
        database, tx = _db(database, tx)
        return _decode(json.loads(host.scriptQuery(database, str(query), "", "false", "update", tx)))

    def runScalarPrepQuery(query, params=None, database=None, tx=None):
        database, tx = _db(database, tx)
        if params is None:
            params = {}
        return _decode(json.loads(host.scriptQuery(database, str(query), json.dumps(params), "true", "scalar", tx)))

    def beginTransaction(database):
        return host.beginTransaction(str(database))

    def commitTransaction(tx):
        host.finishTransaction(str(tx), "true")

    def rollbackTransaction(tx):
        host.finishTransaction(str(tx), "false")

    def closeTransaction(tx):
        host.finishTransaction(str(tx), "false")

    def runNamedQuery(*args):
        if len(args) == 1:
            return execQuery(args[0])
        if len(args) == 2:
            return execQuery(args[0], args[1])
        if len(args) >= 3:
            return execQuery(args[1], args[2], project=args[0])
        raise TypeError("runNamedQuery requires a path")

    def _split_tag(path):
        text = str(path).strip()
        provider = "default"
        relative = text
        if text.startswith("[") and "]" in text:
            end = text.find("]")
            provider = text[1:end] or "default"
            relative = text[end + 1 :]
        return provider, relative.strip("/")

    def configure(path, config):
        body = dict(config or {})
        provider, relative = _split_tag(path)
        body["provider"] = body.get("provider") or provider
        body["path"] = relative
        return host.tagConfigure(json.dumps(body))

    def remove(paths):
        if isinstance(paths, str):
            paths = [paths]
        for path in paths:
            host.tagRemove(str(path))

    delete = remove

    def copy(from_path, to_path):
        return host.tagCopy(str(from_path), str(to_path))

    def move(from_path, to_path):
        return host.tagMove(str(from_path), str(to_path))

    def sendMessage(project, handler, payload=None):
        if payload is None:
            payload = {}
        host.sendMessage(project or "gateway", str(handler), json.dumps(payload))

    def sendRequest(project, handler, payload=None):
        if payload is None:
            payload = {}
        raw = host.sendRequest(project or "gateway", str(handler), json.dumps(payload))
        if raw is None or raw == "":
            return None
        return json.loads(raw)

    tag = types.SimpleNamespace(
        readBlocking=readBlocking, writeBlocking=writeBlocking, browse=browse,
        configure=configure, remove=remove, delete=delete, copy=copy, move=move,
    )
    date = types.SimpleNamespace(
        now=now,
        format=format,
        parse=parse,
        fromMillis=fromMillis,
        getDate=getDate,
        midnight=midnight,
        isAfter=isAfter,
        isBefore=isBefore,
        isBetween=isBetween,
        getTimezone=getTimezone,
        getTimezoneOffset=getTimezoneOffset,
        getTimezoneRawOffset=getTimezoneRawOffset,
        addMillis=_add("millis"),
        addSeconds=_add("seconds"),
        addMinutes=_add("minutes"),
        addHours=_add("hours"),
        addDays=_add("days"),
        addWeeks=_add("weeks"),
        addMonths=_add("months"),
        addYears=_add("years"),
        getMillis=_field("millis"),
        getSecond=_field("second"),
        getMinute=_field("minute"),
        getHour12=_field("hour12"),
        getHour24=_field("hour24"),
        getDayOfWeek=_field("dayOfWeek"),
        getDayOfMonth=_field("dayOfMonth"),
        getDayOfYear=_field("dayOfYear"),
        getMonth=_field("month"),
        getYear=_field("year"),
        getQuarter=_field("quarter"),
        millisBetween=_span("millis"),
        secondsBetween=_span("seconds"),
        minutesBetween=_span("minutes"),
        hoursBetween=_span("hours"),
        daysBetween=_span("days"),
        weeksBetween=_span("weeks"),
        monthsBetween=_span("months"),
        yearsBetween=_span("years"),
    )
    dataset = types.SimpleNamespace(toDataset=toDataset, toPyDataSet=toPyDataSet, filterColumns=filterColumns)
    def scopeHints(scope):
        name = str(scope)
        if name not in ("gateway", "session", "designer"):
            raise ValueError("scope must be gateway, session, or designer")
        shared = [
            "system.tag.readBlocking", "system.tag.writeBlocking", "system.tag.browse",
            "system.date.now", "system.dataset.toDataset", "system.util.jsonEncode",
            "system.db.runNamedQuery", "system.security.getUsername",
            "system.security.validateUser", "system.security.getUserRoles",
            "system.math.mean", "system.math.median", "system.math.percentile", "system.math.standardDeviation",
            "system.project.getProjectName",
        ]
        gateway = shared + [
            "system.file.readFile", "system.http.get", "system.alarm.queryStatus",
            "system.config.listResources", "system.config.getResource", "system.config.putResource", "system.config.deleteResource",
            "system.historian.queryRaw", "system.historian.queryAggregate", "system.historian.store", "system.historian.annotate", "system.historian.browse",
            "system.device.listDevices", "system.device.setDeviceEnabled",
            "system.opc.browse", "system.opc.read", "system.opc.write",
            "system.opcua.listConnections", "system.opcua.callMethod", "system.opcua.getConnectionInfo",
            "system.opcua.addConnection", "system.opcua.removeConnection", "system.opcua.setConnectionEnabled",
            "system.user.listUsers", "system.user.listRoles", "system.user.getUser", "system.user.addUser",
            "system.user.editUser", "system.user.removeUser", "system.user.getSchedules", "system.user.getSchedule",
            "system.user.isScheduleActive", "system.user.getHolidays",
            "system.project.getProjectNames", "system.project.requestScan",
            "system.net.getHostName", "system.net.getIpAddress", "system.net.getIpAddresses", "system.net.getRemoteServers",
            "system.net.httpGet", "system.net.httpPost",
            "system.secrets.listSecrets", "system.secrets.secretExists", "system.secrets.writeSecret", "system.secrets.deleteSecret",
            "system.roster.getRosterNames", "system.roster.getRoster", "system.roster.createRoster",
            "system.roster.setRosterUsers", "system.roster.deleteRoster",
            "system.eventstream.publish", "system.eventstream.getStreams", "system.eventstream.diagnose",
            "system.groups.getGroups", "system.groups.getGroupStatus", "system.groups.setGroupEnabled", "system.groups.executeGroup",
            "system.report.getReportNames", "system.report.executeReport",
            "system.bacnet.readRaw", "system.bacnet.writeRaw", "system.bacnet.writeWithPriority",
            "system.bacnet.synchronizeTime", "system.bacnet.synchronizeTimeUtc",
            "system.dnp.selectOperateBinary", "system.dnp.directOperateBinary",
            "system.dnp.selectOperateAnalog", "system.dnp.directOperateAnalog",
            "system.sfc.startChart", "system.sfc.cancelChart", "system.sfc.pauseChart", "system.sfc.resumeChart",
            "system.sfc.setVariable", "system.sfc.getRunningCharts",
            "system.util.scopeHints",
        ]
        if name == "session":
            return shared + [
                "system.screen.navigate", "system.screen.openPopup", "system.screen.closePopup",
                "system.screen.sendMessage", "system.screen.openDock", "system.screen.closeDock",
                "system.screen.toggleDock", "system.util.scopeHints",
            ]
        return gateway

    def translate(term, locale=None, project=None):
        # The term itself comes back when there is no translation.
        return _lib("util.translate", str(term), None if locale is None else str(locale), None if project is None else str(project))

    util = types.SimpleNamespace(
        translate=translate, getLogger=getLogger, sendMessage=sendMessage, sendRequest=sendRequest,
        jsonEncode=jsonEncode, jsonDecode=jsonDecode, invokeLater=invokeLater, getGatewayStatus=getGatewayStatus,
        scopeHints=scopeHints,
    )
    db = types.SimpleNamespace(
        execQuery=execQuery, runNamedQuery=runNamedQuery,
        runPrepQuery=runPrepQuery, runQuery=runQuery, runPrepUpdate=runPrepUpdate, runUpdateQuery=runUpdateQuery,
        runScalarPrepQuery=runScalarPrepQuery,
        beginTransaction=beginTransaction, commitTransaction=commitTransaction,
        rollbackTransaction=rollbackTransaction, closeTransaction=closeTransaction,
    )

    def _security():
        return json.loads(host.securityUser())

    def getUsername():
        return _security()["username"]

    def getRoles():
        return list(_security()["roles"])

    def getZone():
        return _security()["zone"]

    def queryStatus(source=""):
        return json.loads(host.alarmQueryJson("" if source is None else str(source)))

    def queryJournal(source=""):
        return json.loads(host.alarmJournalJson("" if source is None else str(source)))

    def acknowledge(eventIds, notes=""):
        if isinstance(eventIds, str):
            eventIds = [eventIds]
        host.alarmAck(json.dumps(list(eventIds)), "" if notes is None else str(notes))

    def shelve(source, minutes):
        host.alarmShelveJson(str(source), int(minutes))

    def sendEmail(profile, to, subject, body, fromAddress="", html=False):
        host.emailSendJson(str(profile), str(to), "" if fromAddress is None else str(fromAddress), str(subject), "" if body is None else str(body), bool(html))

    def readFile(path):
        return host.fileRead(str(path))

    def writeFile(path, text):
        host.fileWrite(str(path), "" if text is None else str(text))

    def _http(method, url, body, headers):
        if headers is None:
            headers = {}
        if not isinstance(headers, dict):
            raise TypeError("headers must be a dictionary")
        payload = "" if body is None else body if isinstance(body, str) else json.dumps(body)
        return json.loads(host.httpCall(method, str(url), payload, json.dumps(headers)))

    def httpGet(url, headers=None):
        return _http("GET", url, "", headers)

    def httpPost(url, body="", headers=None):
        return _http("POST", url, body, headers)

    def configList(module, scope, kind):
        return json.loads(host.configList(str(module), str(scope), str(kind)))

    def configGet(module, scope, kind, name):
        return json.loads(host.configGet(str(module), str(scope), str(kind), str(name)))

    def configPut(module, scope, kind, name, spec):
        return json.loads(host.configPut(str(module), str(scope), str(kind), str(name), json.dumps(spec)))

    def configDelete(module, scope, kind, name):
        host.configDelete(str(module), str(scope), str(kind), str(name))

    def historyQuery(path, start, end, aggregate="", interval=0):
        return json.loads(host.historyQuery(str(path), int(start), int(end), str(aggregate), int(interval)))

    def historyStore(path, value, timestamp):
        host.historyStore(str(path), str(value), int(timestamp))

    def historyAnnotate(path, timestamp, note):
        host.historyAnnotate(str(path), int(timestamp), str(note))

    def historyNotes(path):
        return json.loads(host.historyNotes(str(path)))

    def historyBrowse():
        return json.loads(host.historyBrowse())

    def deviceList():
        return json.loads(host.deviceList())

    def deviceEnable(name, enabled):
        host.deviceEnable(str(name), bool(enabled))

    def opcBrowse(device, node=""):
        return json.loads(host.opcBrowse(str(device), str(node)))

    def opcRead(device, node):
        return json.loads(host.opcRead(str(device), str(node)))

    def opcWrite(device, node, value):
        return json.loads(host.opcWrite(str(device), str(node), str(value)))

    def opcuaList():
        return json.loads(host.opcuaList())

    def _lib(function, *args):
        return json.loads(host.libraryCall(function, json.dumps(list(args))))

    def opcuaCall(connection, object_id, method, inputs=None):
        return _lib("opcua.callMethod", str(connection), str(object_id), str(method), [] if inputs is None else list(inputs))

    def opcuaGetConnectionInfo(name):
        return _lib("opcua.getConnectionInfo", str(name))

    def opcuaAddConnection(name, endpointUrl, enabled=True, securityPolicy="NONE", securityMode="NONE"):
        return _lib("opcua.addConnection", str(name), str(endpointUrl), bool(enabled), str(securityPolicy), str(securityMode))

    def opcuaRemoveConnection(name):
        return _lib("opcua.removeConnection", str(name))

    def opcuaSetConnectionEnabled(name, enabled):
        return _lib("opcua.setConnectionEnabled", str(name), bool(enabled))

    def userGetUser(username):
        return _lib("user.getUser", str(username))

    def userAddUser(username, password, roles=None, displayName="", enabled=True):
        return _lib("user.addUser", str(username), str(password), [str(r) for r in (roles or [])], str(displayName), bool(enabled))

    def userEditUser(username, roles=None, displayName=None, enabled=None):
        return _lib("user.editUser", str(username), None if roles is None else [str(r) for r in roles],
                    None if displayName is None else str(displayName), None if enabled is None else bool(enabled))

    def userRemoveUser(username):
        return _lib("user.removeUser", str(username))

    def userGetSchedules():
        return _lib("user.getSchedules")

    def userGetSchedule(name):
        return _lib("user.getSchedule", str(name))

    def userIsScheduleActive(name, timestamp=None):
        return _lib("user.isScheduleActive", str(name), None if timestamp is None else int(timestamp))

    def userGetHolidays():
        return _lib("user.getHolidays")

    def securityValidateUser(username, password):
        return _lib("security.validateUser", str(username), str(password))

    def securityGetUserRoles(username):
        return _lib("security.getUserRoles", str(username))

    def sfcStartChart(path, params=None):
        return _lib("sfc.startChart", str(path), {} if params is None else dict(params))

    def sfcCancelChart(chartId):
        return _lib("sfc.cancelChart", str(chartId))

    def sfcPauseChart(chartId):
        return _lib("sfc.pauseChart", str(chartId))

    def sfcResumeChart(chartId):
        return _lib("sfc.resumeChart", str(chartId))

    def sfcSetVariable(chartId, name, value):
        return _lib("sfc.setVariable", str(chartId), str(name), value)

    def sfcGetRunningCharts(path=None):
        return _lib("sfc.getRunningCharts", None if path is None else str(path))

    def userList():
        return json.loads(host.userList())

    def roleList():
        return json.loads(host.roleList())

    # --- system.math (SCR-014): plain statistics over a list of numbers, run in the script itself ---
    import math as _pm

    def _nums(values, minimum=1):
        if isinstance(values, (str, bytes)) or not hasattr(values, "__iter__"):
            raise TypeError("values must be a list of numbers")
        out = []
        for v in values:
            if isinstance(v, bool) or not isinstance(v, (int, float)):
                raise TypeError("values must be numbers")
            out.append(float(v))
        if len(out) < minimum:
            raise ValueError("at least %d value%s required" % (minimum, "" if minimum == 1 else "s"))
        return out

    def mathSum(values):
        return _pm.fsum(_nums(values, 0))

    def mathMean(values):
        v = _nums(values)
        return _pm.fsum(v) / len(v)

    def mathMin(values):
        return min(_nums(values))

    def mathMax(values):
        return max(_nums(values))

    def mathMedian(values):
        v = sorted(_nums(values))
        n = len(v)
        return v[n // 2] if n % 2 else (v[n // 2 - 1] + v[n // 2]) / 2.0

    def mathMode(values):
        # The most frequent value; of several equally frequent values, the smallest.
        v = _nums(values)
        counts = {}
        for x in v:
            counts[x] = counts.get(x, 0) + 1
        top = max(counts.values())
        return min(x for x, c in counts.items() if c == top)

    def _sumsq(v):
        m = _pm.fsum(v) / len(v)
        return _pm.fsum((x - m) ** 2 for x in v)

    def mathVariance(values):
        v = _nums(values)
        return 0.0 if len(v) == 1 else _sumsq(v) / (len(v) - 1)

    def mathPopulationVariance(values):
        v = _nums(values)
        return _sumsq(v) / len(v)

    def mathStandardDeviation(values):
        return _pm.sqrt(mathVariance(values))

    def mathPopulationStandardDeviation(values):
        return _pm.sqrt(mathPopulationVariance(values))

    def mathGeometricMean(values):
        v = _nums(values)
        if any(x <= 0 for x in v):
            return float("nan")
        return _pm.exp(_pm.fsum(_pm.log(x) for x in v) / len(v))

    def mathSkewness(values):
        # Sample skewness with the usual small-sample correction; NaN below three values.
        v = _nums(values)
        n = len(v)
        if n < 3:
            return float("nan")
        m = _pm.fsum(v) / n
        sd = _pm.sqrt(_sumsq(v) / (n - 1))
        if sd == 0:
            return 0.0
        return n / ((n - 1.0) * (n - 2.0)) * _pm.fsum(((x - m) / sd) ** 3 for x in v)

    def mathKurtosis(values):
        # Excess sample kurtosis with the usual small-sample correction; NaN below four values.
        v = _nums(values)
        n = len(v)
        if n < 4:
            return float("nan")
        m = _pm.fsum(v) / n
        var = _sumsq(v) / (n - 1)
        if var == 0:
            return 0.0
        fourth = _pm.fsum((x - m) ** 4 for x in v)
        return (n * (n + 1.0) / ((n - 1.0) * (n - 2.0) * (n - 3.0)) * fourth / (var * var)
                - 3.0 * (n - 1.0) ** 2 / ((n - 2.0) * (n - 3.0)))

    def mathPercentile(values, percentile):
        # Linear interpolation between the closest ranks at position p * (n + 1) / 100.
        v = sorted(_nums(values))
        p = float(percentile)
        if not (0.0 < p <= 100.0):
            raise ValueError("percentile must be above 0 and at most 100")
        n = len(v)
        pos = p * (n + 1) / 100.0
        if pos < 1:
            return v[0]
        if pos >= n:
            return v[-1]
        lo = int(pos)
        return v[lo - 1] + (pos - lo) * (v[lo] - v[lo - 1])

    def mathSumDifference(first, second):
        a, b = _nums(first, 0), _nums(second, 0)
        if len(a) != len(b):
            raise ValueError("both lists must have the same length")
        return _pm.fsum(x - y for x, y in zip(a, b))

    def mathMeanDifference(first, second):
        n = len(_nums(first))
        return mathSumDifference(first, second) / n

    # --- system.report ---
    def reportGetReportNames():
        return _lib("report.getReportNames")

    def reportExecuteReport(name, parameters=None, distribute=True):
        return _lib("report.executeReport", str(name), {} if parameters is None else dict(parameters), bool(distribute))

    report = types.SimpleNamespace(getReportNames=reportGetReportNames, executeReport=reportExecuteReport)

    math = types.SimpleNamespace(
        sum=mathSum, mean=mathMean, min=mathMin, max=mathMax, median=mathMedian, mode=mathMode,
        variance=mathVariance, populationVariance=mathPopulationVariance,
        standardDeviation=mathStandardDeviation, populationStandardDeviation=mathPopulationStandardDeviation,
        geometricMean=mathGeometricMean, skewness=mathSkewness, kurtosis=mathKurtosis,
        percentile=mathPercentile, sumDifference=mathSumDifference, meanDifference=mathMeanDifference,
    )

    # --- system.project, system.net, system.secrets, system.roster, system.eventstream, system.groups ---
    def projectGetProjectNames():
        return _lib("project.getProjectNames")

    def projectGetProjectName():
        return _lib("project.getProjectName")

    def projectRequestScan():
        return _lib("project.requestScan")

    project = types.SimpleNamespace(
        getProjectNames=projectGetProjectNames, getProjectName=projectGetProjectName, requestScan=projectRequestScan,
    )

    def netGetHostName():
        return _lib("net.getHostName")

    def netGetIpAddress():
        return _lib("net.getIpAddress")

    def netGetIpAddresses():
        return _lib("net.getIpAddresses")

    def netGetRemoteServers(runningOnly=False):
        return _lib("net.getRemoteServers", bool(runningOnly))

    net = types.SimpleNamespace(
        getHostName=netGetHostName, getIpAddress=netGetIpAddress, getIpAddresses=netGetIpAddresses,
        getRemoteServers=netGetRemoteServers, httpGet=httpGet, httpPost=httpPost,
    )

    def secretsList():
        return _lib("secrets.listSecrets")

    def secretsExists(name):
        return _lib("secrets.secretExists", str(name))

    def secretsWrite(name, value, description=""):
        return _lib("secrets.writeSecret", str(name), str(value), "" if description is None else str(description))

    def secretsDelete(name):
        return _lib("secrets.deleteSecret", str(name))

    secrets = types.SimpleNamespace(
        listSecrets=secretsList, secretExists=secretsExists, writeSecret=secretsWrite, deleteSecret=secretsDelete,
    )

    def rosterGetNames():
        return _lib("roster.getRosterNames")

    def rosterGet(name):
        return _lib("roster.getRoster", str(name))

    def rosterCreate(name, users=None, description=""):
        return _lib("roster.createRoster", str(name), [str(u) for u in (users or [])], "" if description is None else str(description))

    def rosterSetUsers(name, users):
        return _lib("roster.setRosterUsers", str(name), [str(u) for u in users])

    def rosterDelete(name):
        return _lib("roster.deleteRoster", str(name))

    roster = types.SimpleNamespace(
        getRosterNames=rosterGetNames, getRoster=rosterGet, createRoster=rosterCreate,
        setRosterUsers=rosterSetUsers, deleteRoster=rosterDelete,
    )

    def eventstreamPublish(name, data=None, metadata=None):
        return _lib("eventstream.publish", str(name), data, {} if metadata is None else dict(metadata))

    def eventstreamGetStreams():
        return _lib("eventstream.getStreams")

    def eventstreamDiagnose(name):
        return _lib("eventstream.diagnose", str(name))

    eventstream = types.SimpleNamespace(
        publish=eventstreamPublish, getStreams=eventstreamGetStreams, diagnose=eventstreamDiagnose,
    )

    def groupsGetGroups():
        return _lib("groups.getGroups")

    def groupsGetStatus(name):
        return _lib("groups.getGroupStatus", str(name))

    def groupsSetEnabled(name, enabled):
        return _lib("groups.setGroupEnabled", str(name), enabled)

    def groupsExecute(name):
        return _lib("groups.executeGroup", str(name))

    groups = types.SimpleNamespace(
        getGroups=groupsGetGroups, getGroupStatus=groupsGetStatus, setGroupEnabled=groupsSetEnabled,
        executeGroup=groupsExecute,
    )

    # --- system.bacnet (SCR-007): raw property access and time sync on a BACnet/IP remote device ---
    def _opt_int(v):
        return None if v is None else int(v)

    def bacnetReadRaw(deviceName, objectType, objectInstance, propertyId, propertyArrayIndex=None):
        return _lib("bacnet.readRaw", str(deviceName), objectType, int(objectInstance), int(propertyId), _opt_int(propertyArrayIndex))

    def bacnetWriteRaw(deviceName, objectType, objectInstance, propertyId, value, priority=None, propertyArrayIndex=None):
        return _lib("bacnet.writeRaw", str(deviceName), objectType, int(objectInstance), int(propertyId), value,
                    _opt_int(priority), _opt_int(propertyArrayIndex))

    def bacnetWriteWithPriority(deviceName, objectType, objectInstance, propertyId, value, priority):
        return bacnetWriteRaw(deviceName, objectType, objectInstance, propertyId, value, priority)

    def bacnetSynchronizeTime(deviceName):
        return _lib("bacnet.synchronizeTime", str(deviceName))

    def bacnetSynchronizeTimeUtc(deviceName):
        return _lib("bacnet.synchronizeTimeUtc", str(deviceName))

    bacnet = types.SimpleNamespace(
        readRaw=bacnetReadRaw, writeRaw=bacnetWriteRaw, writeWithPriority=bacnetWriteWithPriority,
        synchronizeTime=bacnetSynchronizeTime, synchronizeTimeUtc=bacnetSynchronizeTimeUtc,
    )

    # --- system.dnp (SCR-003): DNP3 control relay output and analog output commands ---
    def _dnp_binary(function, deviceName, index, tcc, opType, count, onTime, offTime):
        return _lib(function, str(deviceName), int(index), tcc, opType, _opt_int(count), _opt_int(onTime), _opt_int(offTime))

    def dnpSelectOperateBinary(deviceName, index, tcc, opType, count=1, onTime=0, offTime=0):
        return _dnp_binary("dnp.selectOperateBinary", deviceName, index, tcc, opType, count, onTime, offTime)

    def dnpDirectOperateBinary(deviceName, index, tcc, opType, count=1, onTime=0, offTime=0):
        return _dnp_binary("dnp.directOperateBinary", deviceName, index, tcc, opType, count, onTime, offTime)

    def dnpSelectOperateAnalog(deviceName, index, value, variation=None):
        return _lib("dnp.selectOperateAnalog", str(deviceName), int(index), value, _opt_int(variation))

    def dnpDirectOperateAnalog(deviceName, index, value, variation=None):
        return _lib("dnp.directOperateAnalog", str(deviceName), int(index), value, _opt_int(variation))

    dnp = types.SimpleNamespace(
        selectOperateBinary=dnpSelectOperateBinary, directOperateBinary=dnpDirectOperateBinary,
        selectOperateAnalog=dnpSelectOperateAnalog, directOperateAnalog=dnpDirectOperateAnalog,
    )

    security = types.SimpleNamespace(
        getUsername=getUsername, getRoles=getRoles, getZone=getZone,
        validateUser=securityValidateUser, getUserRoles=securityGetUserRoles,
    )
    alarm = types.SimpleNamespace(queryStatus=queryStatus, queryJournal=queryJournal, acknowledge=acknowledge, shelve=shelve)
    file = types.SimpleNamespace(readFile=readFile, writeFile=writeFile)
    net.sendEmail = sendEmail
    http = types.SimpleNamespace(get=httpGet, post=httpPost)
    config = types.SimpleNamespace(listResources=configList, getResource=configGet, putResource=configPut, deleteResource=configDelete)
    historian = types.SimpleNamespace(
        queryRaw=lambda path, start, end: historyQuery(path, start, end),
        queryAggregate=lambda path, start, end, aggregate, interval: historyQuery(path, start, end, aggregate, interval),
        store=historyStore, annotate=historyAnnotate, notes=historyNotes, browse=historyBrowse,
    )
    device = types.SimpleNamespace(listDevices=deviceList, setDeviceEnabled=deviceEnable)
    opc = types.SimpleNamespace(browse=opcBrowse, read=opcRead, write=opcWrite)
    opcua = types.SimpleNamespace(
        listConnections=opcuaList, callMethod=opcuaCall, getConnectionInfo=opcuaGetConnectionInfo,
        addConnection=opcuaAddConnection, removeConnection=opcuaRemoveConnection,
        setConnectionEnabled=opcuaSetConnectionEnabled,
    )
    user = types.SimpleNamespace(
        listUsers=userList, listRoles=roleList, getUser=userGetUser, addUser=userAddUser, editUser=userEditUser,
        removeUser=userRemoveUser, getSchedules=userGetSchedules, getSchedule=userGetSchedule,
        isScheduleActive=userIsScheduleActive, getHolidays=userGetHolidays,
    )
    sfc = types.SimpleNamespace(
        startChart=sfcStartChart, cancelChart=sfcCancelChart, pauseChart=sfcPauseChart,
        resumeChart=sfcResumeChart, setVariable=sfcSetVariable, getRunningCharts=sfcGetRunningCharts,
    )
    eam = types.SimpleNamespace(
        getGroups=eamGetGroups, queryAgentStatus=eamQueryAgentStatus,
        queryAgentHistory=eamQueryAgentHistory, runTask=eamRunTask,
    )
    return types.SimpleNamespace(
        tag=tag, date=date, dataset=dataset, util=util, db=db, security=security, alarm=alarm, file=file, net=net, http=http,
        config=config, historian=historian, device=device, opc=opc, opcua=opcua, user=user, sfc=sfc, eam=eam,
        math=math, project=project, secrets=secrets, roster=roster, eventstream=eventstream, groups=groups, report=report,
        bacnet=bacnet, dnp=dnp,
    )


def _oip_install_module(qualname, source):
    parts = qualname.split(".")
    parent = None
    for i in range(len(parts) - 1):
        name = ".".join(parts[: i + 1])
        mod = sys.modules.get(name)
        if mod is None:
            mod = types.ModuleType(name)
            mod.__path__ = []
            sys.modules[name] = mod
        if i == 0:
            globals()[parts[0]] = mod
        elif parent is not None:
            setattr(parent, parts[i], mod)
        parent = mod
    module = types.ModuleType(qualname)
    module.__file__ = "lib_" + qualname.replace(".", "_") + ".py"
    leaf = parts[-1]
    if parent is None:
        globals()[leaf] = module
    else:
        setattr(parent, leaf, module)
    sys.modules[qualname] = module
    globs = module.__dict__
    globs["system"] = system
    for key, value in list(globals().items()):
        if isinstance(value, types.ModuleType) and not key.startswith("_"):
            globs.setdefault(key, value)
    exec(compile(source, module.__file__, "exec"), globs)


system = _install_system(_oip_host)
del _oip_host
