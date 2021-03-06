/*-------------------------------------------------------------------------------------------------
 _______ __   _ _______ _______ ______  ______
 |_____| | \  |    |    |______ |     \ |_____]
 |     | |  \_|    |    ______| |_____/ |_____]

 Copyright (c) 2016, antsdb.com and/or its affiliates. All rights reserved. *-xguo0<@

 This program is free software: you can redistribute it and/or modify it under the terms of the
 GNU GNU Lesser General Public License, version 3, as published by the Free Software Foundation.

 You should have received a copy of the GNU Affero General Public License along with this program.
 If not, see <https://www.gnu.org/licenses/lgpl-3.0.en.html>
-------------------------------------------------------------------------------------------------*/
package com.antsdb.saltedfish.minke;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

import org.slf4j.Logger;

import com.antsdb.saltedfish.cpp.BluntHeap;
import com.antsdb.saltedfish.cpp.FileOffset;
import com.antsdb.saltedfish.cpp.KeyBytes;
import com.antsdb.saltedfish.cpp.OutOfHeapMemory;
import com.antsdb.saltedfish.cpp.SkipListScanner;
import com.antsdb.saltedfish.cpp.VariableLengthLongComparator;
import com.antsdb.saltedfish.minke.MinkePage.ScanType;
import com.antsdb.saltedfish.nosql.Recycable;
import com.antsdb.saltedfish.nosql.Row;
import com.antsdb.saltedfish.nosql.ScanOptions;
import com.antsdb.saltedfish.nosql.ScanResult;
import com.antsdb.saltedfish.nosql.ScanResultSynchronizer;
import com.antsdb.saltedfish.nosql.SlowRow;
import com.antsdb.saltedfish.nosql.TableType;
import com.antsdb.saltedfish.nosql.StorageTable;
import com.antsdb.saltedfish.nosql.VaporizingRow;
import com.antsdb.saltedfish.util.CodingError;
import com.antsdb.saltedfish.util.UberUtil;

import static com.antsdb.saltedfish.util.UberFormatter.*;
import static com.antsdb.saltedfish.minke.BoundaryMark.*;

/**
 * 
 * @author *-xguo0<@
 */
public class MinkeTable implements StorageTable, Recycable {
    static final Logger _log = UberUtil.getThisLogger();
    static VariableLengthLongComparator _comp = new VariableLengthLongComparator();
    
    int tableId;
    SafePageSkipList pages = new SafePageSkipList();
    Minke minke;
    TableType type;
    
    static class MyComparator implements Comparator<KeyBytes> {
        @Override
        public int compare(KeyBytes xx, KeyBytes yy) {
            long x = xx.getAddress();
            long y = yy.getAddress();
            if ((x == 0) || (y == 0) || (x == Long.MIN_VALUE) || (y == Long.MIN_VALUE)) {
                return Long.compareUnsigned(x, y);
            }
            return _comp.compare(x, y);
        }
    }
    
    class Scanner extends ScanResult {
        MinkePage.Scanner pageResult;
        long pKey;
        long pRow;
        private boolean isAscending;
        private Range range;
        private KeyBytes start;
        private KeyBytes end;
        private boolean eof = false;

        public Scanner(Range range, boolean isAscending) {
            this.isAscending = isAscending;
            this.start = KeyBytes.alloc(range.pKeyStart);
            this.end = KeyBytes.alloc(range.pKeyEnd);
            this.range = new Range();
            this.range.pKeyStart = this.start.getAddress();
            this.range.startMark = range.startMark;
            this.range.pKeyEnd = this.end.getAddress();
            this.range.endMark = range.endMark;
        }

        MinkePage getPage() {
            return this.pageResult.getPage();
        }
        
        @Override
        public boolean next() {
            for (;;) {
                if (this.eof) {
                    return false;
                }
                if (pageResult == null) {
                    nextPageScan();
                    continue;
                }
                boolean result = this.pageResult.next();
                if (!result) {
                    nextPageScan();
                    continue;
                }
                this.pKey = this.pageResult.getKeyPointer();
                this.pRow = this.pageResult.getRowPointer();
                if (this.pRow < 10) {
                    continue;
                }
                return true;
            }
        }

        void nextPageScan() {
            MinkePage page = (this.pageResult != null) ? this.pageResult.mpage : null;
            this.pageResult = nextPageScan(page);
            if (this.pageResult == null) {
                this.eof = true;
            }
        }
        
        private MinkePage.Scanner nextPageScan(MinkePage page) {
            if (this.isAscending) {
                return nextPageScanAsc(page); 
            }
            else {
                return nextPageScanDesc(page);
            }
        }

        private MinkePage.Scanner nextPageScanDesc(MinkePage page) {
            for (;;) {
                if (page == null) {
                    page = findPageFloor(this.range.pKeyEnd);
                }
                else {
                    page = findPageLower(page.getStartKeyPointer());
                }
                if (page == null) {
                    return null;
                }
                boolean incStart = this.range.endMark == NONE;
                boolean incEnd = this.range.startMark == NONE;
                MinkePage.Scanner sr = page.scan(this.range.pKeyEnd, incStart, this.range.pKeyStart, incEnd, false);
                if (sr != null) {
                    return sr;
                }
            }
        }

        private MinkePage.Scanner nextPageScanAsc(MinkePage page) {
            for (;;) {
                if (page == null) {
                    page = findPageFloor(this.range.pKeyStart);
                }
                else {
                    page = findPageHigher(page.getStartKeyPointer());
                }
                if (page == null) {
                    return null;
                }
                boolean incStart = this.range.startMark == NONE;
                boolean incEnd = this.range.endMark == NONE;
                MinkePage.Scanner sr = page.scan(this.range.pKeyStart, incStart, this.range.pKeyEnd, incEnd, true);
                if (sr != null) {
                    return sr;
                }
            }
        }

        @Override
        public boolean eof() {
            return (this.pageResult == null);
        }

        @Override
        public void close() {
        }

        @Override
        public long getVersion() {
            return this.pageResult.getVersion();
        }

        @Override
        public long getKeyPointer() {
            return this.pageResult.getKeyPointer();
        }

        @Override
        public long getIndexRowKeyPointer() {
            return this.pageResult.getIndexRowKeyPointer();
        }

        @Override
        public long getRowPointer() {
            return this.pageResult.getRowPointer();
        }

        @Override
        public byte getMisc() {
            return this.pageResult.getMisc();
        }

        @Override
        public void rewind() {
            this.pageResult = null;
            this.pKey = 0;
            this.pRow = 0;
        }

        @Override
        public String toString() {
            return this.pageResult.toString();
        }
        
    }
    
    public MinkeTable(Minke cache, int tableId, TableType type) {
        this.minke = cache;
        this.tableId = tableId;
        this.type = type;
    }
    
    public long get(byte[] key) {
        try (BluntHeap heap = new BluntHeap()) {
            long pKey = KeyBytes.allocSet(heap, key).getAddress();
            return get(pKey);
        }
    }
    
    @Override
    public long get(long pKey) {
        MinkePage page = this.pages.getFloorPage(pKey);
        if (page == null) {
            return 0;
        }
        return page.get(pKey);
    }
    
    public long get(KeyBytes key) {
        MinkePage page = this.pages.getFloorPage(key.getAddress());
        if (page == null) {
            return 0;
        }
        return page.get(key.getAddress());
    }
    
    @Override
    public boolean exist(long pKey) {
        return get(pKey) != 0;
    }
    
    @Override
    public long getIndex(long pKey) {
        return get(pKey);
    }
    
    public void put(VaporizingRow row) {
        long pKey = row.getKeyAddress();
        int size = row.getSize();
        for (;;) {
            MinkePage page = this.pages.getFloorPage(pKey);
            if (page == null) {
                grow(pKey);
                continue;
            }
            int counter = page.splitCounter;
            try {
                page.put(row);
                return;
            }
            catch (OutOfPageRange x) {
                grow(pKey);
            }
            catch (OutOfHeapMemory x) {
                split1(counter, page, pKey, size, row);
                return;
            }
        }
    }

    /**
     * split based on the giving key
     * @param pkey
     */
    synchronized void split(long pSplitKey) {
        try {
            MinkePage page = findPageFloor(pSplitKey);
            if (!page.getRange().contains(new Boundary(pSplitKey, NONE))) {
                throw new IllegalArgumentException();
            }
            MinkePage.Scanner scanner = page.scanAll();
            MinkePage newPage1 = this.minke.alloc(this.tableId, page.getStartKeyPointer(), pSplitKey);;
            MinkePage newPage2 = this.minke.alloc(this.tableId, pSplitKey, page.getEndKeyPointer());;
            while (scanner.next()) {
                long pKey = scanner.getKeyPointer();
                MinkePage newpage = (KeyBytes.compare(pKey, pSplitKey) < 0) ? newPage1 : newPage2;
                if (this.type == TableType.DATA) {
                    long version = scanner.getVersion();
                    long pRow = scanner.getRowPointer();
                    Row row = Row.fromMemoryPointer(pRow, version);
                    if (row != null) {
                        newpage.put(row);
                    }
                }
                else {
                    long pRowKey = scanner.getIndexRowKeyPointer();
                    if (pRowKey != 0) {
                        byte misc = scanner.getMisc();
                        newpage.putIndex(pKey, pRowKey, misc);
                    }
                }
            }
            page.splitRanges(newPage1, newPage2);
            if (newPage1 != null) {
                this.pages.put(newPage1);
            }
            if (newPage2 != null) {
                this.pages.put(newPage2);
            }
            this.minke.freePage(page);
        }
        catch (IOException x) {
            throw new MinkeException(x);
        }
    }
    
    private void split1(int counter, MinkePage page, Range range) {
        split1(counter, page, range.pKeyStart, 0, (MergeStream ms)-> {
            ms.setMergeRange(range);
        });
    }

    private long split1(int counter, MinkePage page, long pIncomingKey, int incomingSize, long pRowKey, byte misc) {
        return split1(counter, page, pIncomingKey, incomingSize, (MergeStream ms)-> {
            ms.setMergeIndexData(pIncomingKey, pRowKey, misc);
        });
    }

    private void split1(int counter, MinkePage page, long pIncomingKey, int incomingSize, VaporizingRow row) {
        split1(counter, page, pIncomingKey, incomingSize, (MergeStream ms)-> {
            ms.setMergeData(row);
        });
    }

    private void split1(int counter, MinkePage page, long pIncomingKey, int incomingSize, long pRow) {
        split1(counter, page, pIncomingKey, incomingSize, (MergeStream ms)-> {
            ms.setMergeData(pIncomingKey, pRow);
        });
    }

    private synchronized 
    long split1(int counter, MinkePage page, long pIncomingKey, int incomingSize, Consumer<MergeStream> cb) {
        long result = 0;
        
        // prevents concurrent page splits
        if (counter != page.splitCounter) {
            return result;
        }
        page.splitCounter++;
        
        try {
            // freeze the page
            page.freeze();
            
            // wait for any active operations to end
            while (page.gate.get() != 0) {}
            
            // if original page is not filled up. we do a copy on write
            double newUsageRatio = (page.getUsage() + (double)incomingSize) / this.minke.pageSize;
            if (newUsageRatio < 0.9) {
                copyOnWrite1(page, pIncomingKey, cb);
                return result;
            }
            
            // if incoming key is greater than any keys in original page, we do an append
            long pKeyTail = page.getTailKeyPointer();
            if (pKeyTail == 0) {
                throw new CodingError();
            }
            int cmp = _comp.compare(pIncomingKey, pKeyTail);
            if (cmp > 0) {
                append1(page, pIncomingKey, cb);
                return result;
            }
            
            // at last we do half split
            result = mergeSplit1(page, 0.8f, cb);
            return result;
        }
        catch (IOException x) {
            throw new MinkeException(x);
        }
    }

    private void append1(MinkePage page, long pIncomingKey, Consumer<MergeStream> cb) throws IOException {
        MergeStream stream = new MergeStream(getType(), page);
        
        // get the incoming object into the stream
        
        cb.accept(stream);
        
        // copy ranges that is beyond the incoming key to the new page
        
        MinkePage target = this.minke.alloc(this.tableId, pIncomingKey, page.getEndKeyPointer());
        Range targetRange = target.getRange();
        while (stream.next()) {
            if (stream.isResultRange()) {
                Range range = stream.getRange();
                if (range.hasIntersection(targetRange)) {
                    Range fixedRange = range.intersect(targetRange);
                    target.putRange(fixedRange);
                }
            }
            else if (stream.getKey() == pIncomingKey) {
                stream.apply(target);
            }
        }
        
        // wrap up. update page index
        
        MinkePage deleted = this.pages.put(target);
        if (deleted != null) {
            // this could happen when incoming key is from a Range
            this.minke.freePage(deleted);
        }
        page.setEndKey(pIncomingKey);
        _log.debug("append split: {} -> {} tableId={}", hex(page.id), hex(target.id), this.tableId);
    }

    private void copyOnWrite1(MinkePage page, long pIncomingKey, Consumer<MergeStream> cb) throws IOException {
        mergeSplit1(page, 0.9f, cb);
    }

    private long mergeSplit1(MinkePage page, float fillFactor, Consumer<MergeStream> cb) throws IOException {
        List<MinkePage> newpages = new ArrayList<>();
        MergeStream stream = new MergeStream(getType(), page);
        cb.accept(stream);
        MinkePage target  = this.minke.alloc(this.tableId, page.getStartKey(), page.getEndKey());
        newpages.add(target);
        Range range = null;
        long result = 0;
        while (stream.next()) {
            if (stream.isResultRange()) {
                range = stream.getRange();
                try {
                    if (target.getUsageRatio() >= fillFactor) {
                        throw new OutOfHeapMemory();
                    }
                    target.putRange(range);
                }
                catch (OutOfHeapMemory x) {
                    target.setEndKey(range.pKeyStart);
                    target = this.minke.alloc(this.tableId, range.pKeyStart, page.getEndKeyPointer());
                    newpages.add(target);
                    target.putRange(range);
                }
            }
            else {
                try {
                    if (target.getUsageRatio() >= fillFactor) {
                        throw new OutOfHeapMemory();
                    }
                    stream.apply(target);
                }
                catch (OutOfHeapMemory x) {
                    target.setEndKey(stream.getKey());
                    target = this.minke.alloc(this.tableId, stream.getKey(), page.getEndKeyPointer());
                    newpages.add(target);
                    stream.apply(target);
                    if (range != null) {
                        // range split
                        if (Range.compare(range.pKeyEnd, range.endMark, target.getStartKeyPointer(), 0) >= 0) {
                            Range r = new Range();
                            r.pKeyStart = target.getStartKeyPointer();
                            r.startMark = BoundaryMark.NONE;
                            r.pKeyEnd = range.pKeyEnd;
                            r.endMark = range.endMark;
                            target.putRange(r);
                        }
                    }
                }
            }
        }
        
        // wrap up. update page index
        
        StringBuilder buf = new StringBuilder();
        for (int i=newpages.size()-1; i>=0; i--) {
            MinkePage ii = newpages.get(i);
            MinkePage deleted = this.pages.put(ii);
            if (i!=0 && deleted != null) {
                this.minke.freePage(deleted);
                _log.warn("page leak {}", hex(deleted.id), new Exception());
            }
            buf.insert(0, ',');
            buf.insert(0, hex(ii.id));
        }
        this.minke.freePage(page);
        _log.debug("merge split: {} -> {} tableId={}", hex(page.id), buf.toString(), this.tableId);
        return result;
    }

    private synchronized long split(MinkePage page, 
                                    long pIncomingKey, 
                                    int incomingSize, 
                                    boolean isRange, 
                                    Function<MinkePage, Long> callback) {
        long result = 0;
        try {
            // freeze the page
            
            page.freeze();
            
            // wait for any active operations to end
            
            while (page.gate.get() != 0) {}
            
            // if original page is not filled up. we do a copy on write
            
            double newUsageRatio = (page.getUsage() + (double)incomingSize) / this.minke.pageSize;
            if (newUsageRatio < 0.9) {
                copyOnWrite(page, pIncomingKey, isRange, callback);
                return result;
            }
            
            // if incoming key is greater than any keys in original page, we do an append
            
            long pKeyTail = page.getTailKeyPointer();
            if (pKeyTail == 0) {
                throw new CodingError();
            }
            int cmp = _comp.compare(pIncomingKey, pKeyTail);
            if (cmp > 0) {
                append(page, pIncomingKey, callback);
                return result;
            }
            
            // at last we do half split
            
            result = mergeSplit(page, 0.5f, pIncomingKey, isRange, callback);
            return result;
        }
        catch (IOException x) {
            throw new MinkeException(x);
        }
    }

    synchronized void 
    copyOnWrite(MinkePage page, long pMergeKey, boolean isRange, Function<MinkePage, Long> callback) 
    throws IOException {
        mergeSplit(page, 0.9f, pMergeKey, isRange, callback);
    }

    @SuppressWarnings("unused")
    private synchronized void halfSplit(MinkePage page) throws IOException{
        MinkePage.Scanner scanner = page.scanAll();
        MinkePage newpage = null;
        MinkePage newPage1 = null;
        MinkePage newPage2 = null;
        boolean success = false;
        try {
            if (scanner != null) {
                scanner.skipDeletion = false;
                while (scanner.next()) {
                    long pKey = scanner.getKeyPointer();
                    if (newpage == null) {
                        newpage = this.minke.alloc(this.tableId, page.getStartKey(), page.getEndKey());
                        newPage1 = newpage;
                    }
                    else if (newpage.getUsageRatio() >= 0.5f) {
                        if (newPage2 == null) {
                            newpage = this.minke.alloc(this.tableId, pKey, page.getEndKeyPointer());
                            newPage2 = newpage;
                            newPage1.setEndKey(pKey);
                        }
                    }
                    newpage.put(this.type, scanner);
                }
            }
            if (newPage1 == null) {
                newPage1 = this.minke.alloc(this.tableId, page.getStartKey(), page.getEndKey());
            }
            if ((newPage1 != null) && (newPage2 != null)) {
                page.splitRanges(newPage1, newPage2);
            }
            else {
                page.copyRanges(newPage1);
            }
            success = true;
        }
        finally {
            if (!success) {
                // potential page leak
                if (newPage1 != null) {
                    this.minke.freePage(newPage1);
                }
                if (newPage2 != null) {
                    this.minke.freePage(newPage2);
                }
            }
        }
        if (newPage2 != null) {
            MinkePage deleted = this.pages.put(newPage2);
            if (deleted != null) {
                _log.warn("page leak {}", hex(deleted.id), new Exception());
            }
        }
        if (newPage1 != null) {
            MinkePage deleted = this.pages.put(newPage1);
            if (deleted != page) {
                _log.warn("page leak {}", hex(deleted.id), new Exception());
            }
        }
        this.minke.freePage(page);
        _log.debug("half split: {} -> {},{} tableId={}", 
                hex(page.id), 
                hex(newPage1.id), 
                newPage2 == null ? "null" : hex(newPage2.id),
                this.tableId);
    }

    private synchronized void append(MinkePage page, long pIncomingKey, Function<MinkePage, Long> callback) 
    throws IOException {
        MinkePage newpage = this.minke.alloc(this.tableId, pIncomingKey, page.getEndKeyPointer());
        SkipListScanner sr = page.ranges.scan(0, true, 0, true);
        if (sr != null) {
            while (sr.next()) {
                long pRange = sr.getValuePointer();
                Range range = page.toRange(pRange);
                if (range == null) {
                    continue;
                }
                if (KeyBytes.compare(range.pKeyStart, pIncomingKey) >= 0) {
                    newpage.putRange(range);
                }
                else if (KeyBytes.compare(range.pKeyEnd, pIncomingKey) > 0) {
                    range.pKeyStart = pIncomingKey;
                    range.startMark = BoundaryMark.NONE;
                    newpage.putRange(range);
                }
            }
        }
        callback.apply(newpage);
        MinkePage deleted = this.pages.put(newpage);
        if (deleted != null) {
            this.minke.freePage(deleted);
        }
        page.setEndKey(pIncomingKey);
        _log.debug("append split: {} -> {} tableId={}", hex(page.id), hex(newpage.id), this.tableId);
    }

    public void put(SlowRow row) {
        BluntHeap heap = new BluntHeap();
        try {
            put(row.toVaporisingRow(heap));
        }
        finally {
            heap.close();
        }
    }

    public void put(Row row) {
        long pKey = row.getKeyAddress();
        int size = row.getLength();
        for (;;) {
            MinkePage page = this.pages.getFloorPage(pKey);
            if (page == null) {
                grow(pKey);
                continue;
            }
            int counter = page.splitCounter;
            try {
                page.put(row);
                return;
            }
            catch (OutOfPageRange x) {
                grow(pKey);
            }
            catch (OutOfHeapMemory x) {
                split1(counter, page, pKey, size, row.getAddress());
                return;
            }
        }
    }
    
    private long writePage(long pKey, int size, Function<MinkePage, Long> callback) {
        for (;;) {
            MinkePage page = this.pages.getFloorPage(pKey);
            if (page == null) {
                grow(pKey);
                continue;
            }
            try {
                return callback.apply(page);
            }
            catch (OutOfPageRange x) {
                grow(pKey);
            }
            catch (OutOfHeapMemory x) {
                return split(page, pKey, size, false, (it) -> {
                    return callback.apply(it);
                });
            }
        }
    }

    public long put_(Row row) {
        long pKey = row.getKeyAddress();
        int size = row.getLength();
        return writePage(pKey, size, (MinkePage page)-> {
            return page.put(row);
        });
    }

    public void putDeleteMark(long pKey) {
        for (;;) {
            MinkePage page = this.pages.getFloorPage(pKey);
            if (page == null) {
                grow(pKey);
                continue;
            }
            int counter = page.splitCounter;
            try {
                page.putDeleteMark(pKey);
                return;
            }
            catch (OutOfPageRange x) {
                grow(pKey);
            }
            catch (OutOfHeapMemory x) {
                split1(counter, page, pKey, 0, Row.DELETE_MARK);
                return;
            }
        }
    }
    
    public void putIndex(byte[] indexKey, byte[] rowKey, byte misc) {
        try (BluntHeap heap = new BluntHeap()) {
            long pIndexKey = KeyBytes.allocSet(heap, indexKey).getAddress();
            long pRowKey = KeyBytes.allocSet(heap, rowKey).getAddress();
            putIndex(pIndexKey, pRowKey, misc);
        }
    }
    
    public void putIndex(long pIndexKey, long pRowKey, byte misc) {
        putIndex_(pIndexKey, pRowKey, misc);
    }
    
    long putIndex_(long pIndexKey, long pRowKey, byte misc) {
        for (;;) {
            MinkePage page = this.pages.getFloorPage(pIndexKey);
            if (page == null) {
                grow(pIndexKey);
                continue;
            }
            int counter = page.splitCounter;
            try {
                return page.putIndex(pIndexKey, pRowKey, misc);
            }
            catch (OutOfPageRange x) {
                grow(pIndexKey);
            }
            catch (OutOfHeapMemory x) {
                int size = KeyBytes.getRawSize(pIndexKey) + KeyBytes.getRawSize(pRowKey) + 1;
                 return split1(counter, page, pIndexKey, size, pRowKey, misc);
            }
        }
    }

    public void delete(byte[] key) {
        try (BluntHeap heap = new BluntHeap()) {
            long pKey = KeyBytes.allocSet(heap, key).getAddress();
            delete(pKey);
        }
    }
    
    public void delete(long pKey) {
        for (;;) {
            MinkePage page = this.pages.getFloorPage(pKey);
            if (page == null) {
                grow(pKey);
                continue;
            }
            int counter = page.splitCounter;
            try {
                page.delete(pKey);
                return;
            }
            catch (OutOfPageRange x) {
                grow(pKey);
            }
            catch (OutOfHeapMemory x) {
                split1(counter, page, pKey, 0, 0);
                return;
            }
        }
    }
    
    public void drop() {
        for (MinkePage i:this.pages.values()) {
            this.minke.freePage(i);
        }
        this.pages.clear();
    }
    
    ScanResult scan(byte[] keyStart, byte[] keyEnd, long options) {
        try (BluntHeap heap = new BluntHeap()) {
            long pKeyStart = KeyBytes.allocSet(heap, keyStart).getAddress();
            long pKeyEnd = KeyBytes.allocSet(heap, keyEnd).getAddress();
            return scan(pKeyStart, pKeyEnd, options);
        }
    }
    
    @Override
    public ScanResult scan(long pKeyStart, long pKeyEnd, long options) {
        boolean includeStart = ScanOptions.includeStart(options);
        boolean includeEnd = ScanOptions.includeEnd(options);
        boolean isAscending = ScanOptions.isAscending(options);
        Range range;
        if (isAscending) {
            range = new Range(pKeyStart, includeStart, pKeyEnd, includeEnd);
        }
        else {
            range = new Range(pKeyEnd, includeEnd, pKeyStart, includeStart);
        }
        Scanner sr = new Scanner(range, isAscending);
        return sr;
    }

    public FindRangesResult findRanges(Range range, boolean asc) {
        boolean incStart = range.startMark == NONE;
        boolean incEnd = range.endMark == NONE;
        return findRanges(range.pKeyStart, incStart, range.pKeyEnd, incEnd, asc);
    }
    
    public FindRangesResult findRanges(long pStart, boolean incStart, long pEnd, boolean incEnd, boolean asc) {
        FindRangesResult result = new FindRangesResult(this);
        result.setRange(pStart, incStart, pEnd, incEnd, asc);
        return result;
    }
    
    public void read(long version, DataInputStream in) throws IOException, ClassNotFoundException {
        for (;;) {
            int pageId = in.readInt();
            if (pageId == -1) {
                return;
            }
            MinkePage mpage = this.minke.getPage(pageId);
            if (version >= 1) {
                mpage.lastAccess.set(in.readLong()); 
            }
            KeyBytes startKey = readKeyBytes(in);
            KeyBytes endKey = readKeyBytes(in);
            mpage.assign(this.tableId, startKey, endKey);
            this.pages.put(mpage);
        }
    }
    
    static KeyBytes readKeyBytes(DataInputStream in) throws IOException {
        int len = in.readShort();
        if (len == 0) {
            return null;
        }
        byte[] bytes = new byte[len];
        in.read(bytes);
        KeyBytes key = KeyBytes.alloc(bytes);
        return key;
    }

    public void write(DataOutputStream out) throws IOException {
        for (Map.Entry<KeyBytes, MinkePage> i:this.pages.entrySet()) {
            MinkePage ii = i.getValue();
            out.writeInt(ii.id);
            out.writeLong(ii.lastAccess.get());
            writeKeyBytes(out, ii.getStartKey());
            writeKeyBytes(out, ii.getEndKey());
        }
        out.writeInt(-1);
    }
    
    private void writeKeyBytes(DataOutputStream out, KeyBytes key) throws IOException {
        if (key == null) {
            out.writeShort(0);
        }
        else {
            byte[] bytes = key.get();
            out.writeShort(bytes.length);
            out.write(bytes);
        }
    }

    public Collection<MinkePage> getPages() {
        return this.pages.values();
    }
    
    public int getPageCount() {
        return getPages().size();
    }
    
    TableType getType() {
        return this.type;
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        for (MinkePage i:this.pages.values()) {
            buf.append(i.toString());
            buf.append('\n');
        }
        return buf.toString();
    }
    
    void validate() {
        for (Map.Entry<KeyBytes, MinkePage> i:this.pages.entrySet()) {
            MinkePage page = i.getValue();
            if (i.getKey() != page.getStartKey()) {
                throw new MinkeException(
                        "page {}-{} is with wrong key {}",
                        hex(page.id),
                        (Long)page.getStartKeyPointer(), 
                        i.getKey());
            }
        }
    }

    public Range findRange(Boundary boundary) {
        MinkePage page = findPageFloor(boundary.pKey);
        if (page == null) {
            return null;
        }
        return page.findRange(boundary);
    }
    
    public boolean findRange(Range request, Range result) {
        Map.Entry<KeyBytes, MinkePage> entry = this.pages.floorEntry(request.pKeyStart);
        if (entry != null) {
            for (;;) {
                MinkePage page = this.pages.floorEntry(request.pKeyStart).getValue();
                MinkePage.ScanType scantype = page.findRange(request, result);
                if (scantype == ScanType.CACHE) {
                    return true;
                }
                if (scantype == ScanType.STORAGE) {
                    return false;
                }
                entry = this.pages.higherEntry(page.getTailKeyPointer());
                if (entry == null) {
                    break;
                }
            }
        }
        result.pKeyStart = request.pKeyStart;
        result.startMark = request.startMark;
        result.pKeyEnd = request.pKeyEnd;
        result.endMark = request.endMark;
        return false;
    }
    
    public void putRange(Range range) {
        for (;;) {
            if (range.isEmpty()) {
                break;
            }
            MinkePage page = findPageFloor(range.pKeyStart);
            if (page == null) {
                grow(range.getStart().pKey);
                continue;
            }
            int counter = page.splitCounter;
            Range pageRange = page.getRange();
            if (!pageRange.contains(range.getStart())) {
                grow(range.getStart().pKey);
                continue;
            }
            Range localRange = range.intersect(pageRange);
            try {
                page.putRange(localRange);
            }
            catch (OutOfPageRange x) {
                grow(range.getStart().pKey);
            }
            catch (OutOfHeapMemory x) {
                split1(counter, page, localRange);
            }
            if (range.equals(localRange)) {
                // input range is contained in single page, end it
                break;
            }
            range = range.minus(localRange);
        }
    }
    
    private MinkePage findPageFloor(long pkey) {
        Map.Entry<KeyBytes, MinkePage> entry = this.pages.floorEntry(pkey);
        return (entry != null) ? entry.getValue() : null;
    }
    
    private MinkePage findPageHigher(long pkey) {
        Map.Entry<KeyBytes, MinkePage> entry = this.pages.higherEntry(pkey);
        return (entry != null) ? entry.getValue() : null;

    }
    private MinkePage findPageLower(long pkey) {
        Map.Entry<KeyBytes, MinkePage> entry = this.pages.lowerEntry(pkey);
        return (entry != null) ? entry.getValue() : null;
    }

    public MinkePage findCeilingPage(long pKey, int mark) {
        MinkePage page = this.pages.getFloorPage(pKey);
        if (page != null) {
            // is the input in the page range?
            if (Range.compare(pKey, mark, page.getEndKeyPointer(), BoundaryMark.MINUS) <= 0) {
                return page;
            }
        }
        page = this.pages.getHigherPage(pKey);
        return page;
    }
    
    /**
     * find the range that covers x or greater than x
     * @param x
     * @param ascending 
     */
    public ScanResult findNextRangeAndDoShit(Range x, FindNextRangeAndDoShitCallback callback, boolean ascending) {
        if (ascending) {
            MinkePage page = this.pages.getLowerPage(x.pKeyStart);
            if (page != null) {
                ScanResult result = page.findNextRangeAndDoShit(x, callback, ascending);
                if (result != null) {
                    return result;
                }
            }
            for (long pKey=x.pKeyStart;;) {
                page = this.pages.getCeilingPage(pKey);
                if (page == null) {
                    break;
                }
                if (KeyBytes.compare(page.getStartKeyPointer(), x.pKeyEnd) > 0) {
                    break;
                }
                ScanResult result = page.findNextRangeAndDoShit(x, callback, ascending);
                if (result != null) {
                    return result;
                }
                pKey = page.getEndKeyPointer();
            }
        }
        else {
            MinkePage page = this.pages.getFloorPage(x.pKeyEnd);
            if (page != null) {
                ScanResult result = page.findNextRangeAndDoShit(x, callback, ascending);
                if (result != null) {
                    return result;
                }
            }
            for (long pKey=x.pKeyEnd;;) {
                page = this.pages.getLowerPage(pKey);
                if (page == null) {
                    break;
                }
                if (KeyBytes.compare(page.getEndKeyPointer(), x.pKeyStart) < 0) {
                    break;
                }
                ScanResult result = page.findNextRangeAndDoShit(x, callback, ascending);
                if (result != null) {
                    return result;
                }
                pKey = page.getStartKeyPointer();
            }
        }
        return null;
    }

    /**
     * copy a row from the source
     * 
     * @param cacheResultFilter
     */
    void copyEntry(ScanResult source) {
        ScanResultSynchronizer.synchronizeSingleEntry(source, this, this.type);
    }

    public void traversePages(Consumer<MinkePage> callback) {
        for (MinkePage i:getPages()) {
            callback.accept(i);
        }
    }

    public boolean deletePage(MinkePage page) {
        boolean result = this.pages.remove(page);
        if (result) {
            this.minke.freePage(page);
        }
        return result;
    }

    void deleteAllPages() {
        for (MinkePage i:getPages()) {
            this.minke.freePage(i);
        }
        this.pages.clear();
    }
    
    @Override
    public void recycle() {
        deleteAllPages();
    }

    synchronized MinkePage grow(long pKey) {
        Map.Entry<KeyBytes, MinkePage> floor = this.pages.floorEntry(pKey);
        Map.Entry<KeyBytes, MinkePage> ceil = this.pages.ceilingEntry(pKey);
        if (floor != null) {
            MinkePage page = floor.getValue();
            if (KeyBytes.compare(pKey, page.getEndKeyPointer()) < 0) {
                // page was just created by a concurrent thread
                return page;
            }
        }
        long pKeyStart = (floor != null) ? floor.getValue().getEndKeyPointer() : KeyBytes.getMinKey();
        long pKeyEnd = (ceil != null) ? ceil.getValue().getStartKeyPointer() : KeyBytes.getMaxKey();
        MinkePage result = allocPage(pKeyStart, pKeyEnd);
        _log.debug("growing with a new page {}", hex(result.id));
        return result;
    }

    synchronized MinkePage allocPage(long pStartKey, long pEndKey) {
        try {
            MinkePage page = this.minke.alloc(this.tableId, pStartKey, pEndKey);
            MinkePage deleted = this.pages.put(page);
            if (deleted != null) {
                _log.warn("page leak {}", hex(deleted.id), new Exception());
            }
            return page;
        }
        catch (IOException x) {
            throw new MinkeException(x);
        }
    }

    @Override
    public String getLocation(long pKey) {
        MinkePage page = this.pages.getFloorPage(pKey);
        if (page == null) {
            return "";
        }
        return page.getLocation(pKey);
    }

    @Override
    public boolean traceIo(long pKey, List<FileOffset> lines) {
        MinkePage page = this.pages.getFloorPage(pKey);
        if (page == null) {
            return false;
        }
        return page.traceIo(pKey, lines);
    }

    synchronized 
    long mergeSplit(MinkePage page, float fillFactor, long pMergeKey, boolean isRange, Function<MinkePage, Long> callback)
    throws IOException {
        List<MinkePage> newpages = new ArrayList<>();
        MergeStream stream = new MergeStream(getType(), page);
        MinkePage target  = this.minke.alloc(this.tableId, page.getStartKey(), page.getEndKey());
        newpages.add(target);
        Range range = null;
        long result = 0;
        while (stream.next()) {
            if (stream.getWinner() == MergeStream.WINNER_MERGE) {
                // winner is the merge data
                try {
                    if (target.getUsageRatio() >= fillFactor) {
                        throw new OutOfHeapMemory();
                    }
                    result = callback.apply(target);
                }
                catch (OutOfHeapMemory x) {
                    target.setEndKey(stream.getKey());
                    target = this.minke.alloc(this.tableId, stream.getKey(), page.getEndKeyPointer());
                    newpages.add(target);
                    result = callback.apply(target);
                }
            }
            else if (stream.getWinner() == MergeStream.WINNER_RANGE) {
                // winner is a range
                range = stream.getRange();
                try {
                    if (target.getUsageRatio() >= fillFactor) {
                        throw new OutOfHeapMemory();
                    }
                    target.putRange(range);
                }
                catch (OutOfHeapMemory x) {
                    target.setEndKey(range.pKeyStart);
                    target = this.minke.alloc(this.tableId, range.pKeyStart, page.getEndKeyPointer());
                    newpages.add(target);
                    target.putRange(range);
                }
            }
            else if (stream.getWinner() == MergeStream.WINNER_NON_RANGE) {
                // winner is a non-range
                long pKey = stream.getKey();
                long pData = stream.getData();
                try {
                    if (target.getUsageRatio() >= fillFactor) {
                        throw new OutOfHeapMemory();
                    }
                    target.put(this.type, pKey, pData, 0);
                }
                catch (OutOfHeapMemory x) {
                    target.setEndKey(pKey);
                    target = this.minke.alloc(this.tableId, pKey, page.getEndKeyPointer());
                    newpages.add(target);
                    if (range != null) {
                        // range split
                        if (Range.compare(range.pKeyEnd, range.endMark, target.getStartKeyPointer(), 0) >= 0) {
                            range.pKeyStart = target.getStartKeyPointer();
                            range.startMark = 0;
                            target.putRange(range);
                        }
                    }
                    target.put(this.type, pKey, pData, 0);
                }
            }
            else {
                throw new IllegalArgumentException();
            }
        }
        
        // wrap up. update page index
        
        StringBuilder buf = new StringBuilder();
        for (int i=newpages.size()-1; i>=0; i--) {
            MinkePage ii = newpages.get(i);
            MinkePage deleted = this.pages.put(ii);
            if (i!=0 && deleted != null) {
                this.minke.freePage(deleted);
                _log.warn("page leak {}", hex(deleted.id), new Exception());
            }
            buf.insert(0, ',');
            buf.insert(0, hex(ii.id));
        }
        this.minke.freePage(page);
        _log.debug("merge split: {} -> {} tableId={}", hex(page.id), buf.toString(), this.tableId);
        return result;
    }
}
