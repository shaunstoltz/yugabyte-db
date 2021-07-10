#!/usr/bin/env python3

"""
Generic binary transaction dump parser.
"""

import os
import sys

from abc import ABC, abstractmethod
from enum import Enum
from functools import total_ordering
from io import BytesIO
from time import monotonic
from typing import Any, Dict, List, NamedTuple, Optional, Set
from uuid import UUID
from yb.txndump.io import BinaryIO
from yb.txndump.model import DocHybridTime, HybridTime, ReadHybridTime, SubDocKey, \
    TransactionStatus, decode_value, read_txn_id


class CommitTimeReason(Enum):
    kLocalBefore = 0
    kNoMetadata = 1
    kLocalAfter = 2
    kRemoteAborted = 3
    kRemoteCommitted = 4
    kRemotePending = 5


class RemoveReason(Enum):
    kApplied = 0
    kLargeApplied = 1
    kProcessCleanup = 2
    kStatusReceived = 3
    kAbortReceived = 4


class WriteBatchEntryType(Enum):
    kTypeDeletion = 0x0
    kTypeValue = 0x1
    kTypeMerge = 0x2
    kTypeLogData = 0x3
    kTypeColumnFamilyDeletion = 0x4
    kTypeColumnFamilyValue = 0x5
    kTypeColumnFamilyMerge = 0x6
    kTypeSingleDeletion = 0x7
    kTypeColumnFamilySingleDeletion = 0x8


class TransactionConflictData(NamedTuple):
    id: UUID = UUID(int=0)
    status: TransactionStatus = TransactionStatus.UNKNOWN
    commit_time: HybridTime = HybridTime()
    priority: int = 0
    failed: bool = False


def load_transaction_conflict_data(inp: BinaryIO) -> Optional[TransactionConflictData]:
    txn_id = read_txn_id(inp)
    if txn_id is None:
        return None
    status = TransactionStatus(inp.read_uint32())
    inp.read_uint32()
    commit_time = HybridTime.load(inp)
    priority = inp.read_uint64()
    failed = inp.read_uint64() != 0
    return TransactionConflictData(txn_id, status, commit_time, priority, failed)


class StatusLogEntry(NamedTuple):
    read_time: ReadHybridTime
    by_txn_id: UUID
    commit_time: HybridTime
    reason: CommitTimeReason


class Analyzer:
    @abstractmethod
    def get_transaction(self, txn_id: UUID):
        pass

    @abstractmethod
    def apply_row(self, txn_id: UUID, key: SubDocKey, value, log_ht: HybridTime):
        pass

    @abstractmethod
    def read_value(
            self, txn_id: UUID, key: SubDocKey, value, read_time: HybridTime,
            write_time: DocHybridTime, same_transaction: bool):
        pass


class DumpProcessor:
    def __init__(self, analyzer):
        self.cnt_commands = 0
        self.start_time = monotonic()
        self.analyzer = analyzer
        self.txns = {}
        self.commands = {
            1: DumpProcessor.parse_apply,
            2: DumpProcessor.parse_read,
            3: DumpProcessor.parse_commit,
            4: DumpProcessor.parse_status,
            5: DumpProcessor.parse_conflicts,
            6: DumpProcessor.parse_applied,
            7: DumpProcessor.parse_remove,
        }

    def process(self, input_path: str):
        if os.path.isdir(input_path):
            for file in os.listdir(input_path):
                if file.startswith("DUMP."):
                    self.process_file(os.path.join(input_path, file))
        else:
            self.process_file(input_path)

    def process_file(self, fname: str):
        # path, processing_file = os.path.split(fname)
        print("Processing {}".format(fname))
        with open(fname, 'rb') as raw_input:
            inp = BinaryIO(raw_input)
            while True:
                size = inp.read_int64()
                if size is None:
                    break
                body = inp.read(size)
                block = BinaryIO(BytesIO(body))
                cmd = block.read_int8()
                self.commands[cmd](self, block)
                left = block.read()
                if len(left) != 0:
                    raise Exception("Extra data left in block {}: {}".format(cmd, left))
                self.cnt_commands += 1
                if self.cnt_commands % 10000 == 0:
                    print("Parsed {} commands, passed: {}".format(
                        self.cnt_commands, monotonic() - self.start_time))

    def parse_apply(self, inp: BinaryIO):
        txn = read_txn_id(inp)
        log_ht = HybridTime.load(inp)
        inp.read_int64()  # Sequence no
        count = inp.read_int32()
        for i in range(0, count):
            cmd = WriteBatchEntryType(inp.read_int8())
            if cmd == WriteBatchEntryType.kTypeValue:
                key = SubDocKey.decode(inp.read_varbytes(), True)
                value = decode_value(inp.read_varbytes())
                self.analyzer.apply_row(txn, key, value, log_ht)
            else:
                raise Exception('Not supported write batch entry type: {}'.format(cmd))

    def parse_read(self, inp: BinaryIO):
        txn = read_txn_id(inp)
        read_time = ReadHybridTime.load(inp)
        write_time = DocHybridTime.load(inp)
        same_transaction = inp.read_bool()
        key_len = inp.read_uint64()
        key_bytes = inp.read(key_len)
        key = SubDocKey.decode(key_bytes, False)
        value_len = inp.read_uint64()
        value = decode_value(inp.read(value_len))
        self.analyzer.read_value(txn, key, value, read_time.read, write_time, same_transaction)

    def parse_commit(self, inp: BinaryIO):
        txn_id = read_txn_id(inp)
        commit_time = HybridTime.load(inp)
        txn = self.get_transaction(txn_id)
        tablets = inp.read_uint32()
        if txn.commit_time.valid():
            if txn.commit_time != commit_time:
                raise Exception('Wrong commit time {} vs {}'.format(commit_time, txn))
        else:
            txn.commit_time = commit_time
            txn.involved_tablets = tablets

    def parse_status(self, inp: BinaryIO):
        by_txn = read_txn_id(inp)
        read_time = ReadHybridTime.load(inp)
        txn_id = read_txn_id(inp)
        commit_time = HybridTime.load(inp)
        reason = CommitTimeReason(inp.read_uint8())
        status_time = HybridTime.load(inp)
        safe_time = HybridTime.load(inp)
        txn = self.get_transaction(txn_id)
        txn.status_log.append(StatusLogEntry(read_time, by_txn, commit_time, reason))
        txn.add_log(
            read_time.read, "status check by", by_txn, commit_time, reason, status_time, safe_time)
        self.get_transaction(by_txn).add_log(
            read_time.read, "see status", txn_id, commit_time, reason, status_time, safe_time)

    def parse_conflicts(self, inp: BinaryIO):
        txn_id = read_txn_id(inp)
        hybrid_time = HybridTime.load(inp)
        txn = self.get_transaction(txn_id)
        if not hybrid_time.valid():
            txn.aborted = True
        while True:
            txn_data = load_transaction_conflict_data(inp)
            if txn_data is None:
                break
            txn.add_log(hybrid_time, "see conflict", txn_data)
            self.get_transaction(txn_data.id).add_log(
                hybrid_time, "conflict check by", txn_id, txn_data)

    def parse_applied(self, inp: BinaryIO):
        txn_id = read_txn_id(inp)
        hybrid_time = HybridTime.load(inp)
        self.get_transaction(txn_id).add_log(hybrid_time, "applied")

    def parse_remove(self, inp: BinaryIO):
        txn_id = read_txn_id(inp)
        hybrid_time = HybridTime.load(inp)
        reason = RemoveReason(inp.read_uint8())
        self.get_transaction(txn_id).add_log(hybrid_time, "remove", reason)

    def get_transaction(self, txn_id: UUID):
        return self.analyzer.get_transaction(txn_id)


class Error(NamedTuple):
    hybrid_time: HybridTime
    msg: str


class TxnLogEntry(NamedTuple):
    hybrid_time: HybridTime
    op: str
    args: Any


@total_ordering
class TransactionBase:
    def __init__(self, txn_id: UUID):
        self.id = txn_id
        self.involved_tablets = None
        self.commit_time = HybridTime()
        self.status_log: List[StatusLogEntry] = []
        self.aborted = False
        self.log: List[TxnLogEntry] = []

    def add_log(self, hybrid_time: HybridTime, op: str, *args):
        self.log.append(TxnLogEntry(hybrid_time, op, args))

    def __lt__(self, other) -> bool:
        return (self.id, self.commit_time) < (other.id, other.commit_time)

    def report(self):
        print("TXN: {}".format(self))
        for entry in sorted(self.log, key=lambda t: t.hybrid_time):
            print("  log: {}".format(entry))

    def fields_to_string(self):
        return "id: {} commit_time: {}".format(self.id, self.commit_time)


class AnalyzerBase(Analyzer, ABC):
    def __init__(self):
        self.txns: Dict[UUID, TransactionBase] = {}
        self.errors: List[Error] = []
        self.reported_transactions: Set[UUID] = set()

    def report_errors(self):
        for err in sorted(self.errors, key=lambda error: error.hybrid_time):
            sys.stderr.write(err.msg + "\n")
        return len(self.errors) != 0

    def error(self, error_time: HybridTime, txn_id: UUID, msg: str):
        self.errors.append(Error(error_time, msg))
        self.report_transaction(txn_id)
        if len(self.errors) >= 10:
            self.report_errors()
            sys.stderr.write("Too many errors, exiting\n")
            exit(1)

    def report_transaction(self, txn_id: UUID):
        if txn_id in self.reported_transactions:
            return
        self.txns[txn_id].report()
        self.reported_transactions.add(txn_id)

    def check_status_logs(self):
        for txn in self.txns.values():
            for entry in txn.status_log:
                by_txn_id = entry.by_txn_id
                if by_txn_id in self.txns and self.txns[by_txn_id].aborted:
                    continue
                seen_commit_time = entry.commit_time
                reason = entry.reason
                if txn.commit_time.valid():
                    if not seen_commit_time.is_min():
                        if seen_commit_time != txn.commit_time:
                            self.error(
                                entry.read_time.read, txn.id,
                                "Seen commit time mismatch: {} vs {}".format(
                                    seen_commit_time, txn))
                    elif entry.read_time.read >= txn.commit_time \
                            and reason != CommitTimeReason.kNoMetadata:
                        self.error(
                            entry.read_time.read, txn.id,
                            "Did not see commit of {} at {} by {} reason {}".format(
                                txn, entry.read_time.read, by_txn_id, entry.reason))
                    elif entry.reason == CommitTimeReason.kRemoteAborted:
                        self.error(
                            entry.read_time.read, txn.id,
                            "Committed transaction {} seen as aborted at {}".format(
                                txn, entry.read_time.read))
                elif not seen_commit_time.is_min():
                    self.error(
                        entry.read_time.read, txn.id,
                        "Aborted transaction seen as committed: {} by {} vs {}".format(
                            seen_commit_time, by_txn_id, txn))
