package edu.berkeley.cs186.database.query;

import edu.berkeley.cs186.database.Database;
import edu.berkeley.cs186.database.DatabaseException;
import edu.berkeley.cs186.database.databox.DataBox;
import edu.berkeley.cs186.database.table.Record;
import edu.berkeley.cs186.database.table.RecordId;
import edu.berkeley.cs186.database.table.RecordIterator;
import edu.berkeley.cs186.database.table.Schema;
import edu.berkeley.cs186.database.common.Pair;
import edu.berkeley.cs186.database.io.Page;

import javax.xml.crypto.Data;
import java.util.*;


public class SortOperator  {
  private Database.Transaction transaction;
  private String tableName;
  private Comparator<Record> comparator;
  private Schema operatorSchema;
  private int numBuffers;

  public SortOperator(Database.Transaction transaction, String tableName, Comparator<Record> comparator) throws DatabaseException, QueryPlanException {
    this.transaction = transaction;
    this.tableName = tableName;
    this.comparator = comparator;
    this.operatorSchema = this.computeSchema();
    this.numBuffers = this.transaction.getNumMemoryPages();
  }

  public Schema computeSchema() throws QueryPlanException {
    try {
      return this.transaction.getFullyQualifiedSchema(this.tableName);
    } catch (DatabaseException de) {
      throw new QueryPlanException(de);
    }
  }


  public class Run {
    String tempTableName;

    public Run() throws DatabaseException {
      this.tempTableName = SortOperator.this.transaction.createTempTable(SortOperator.this.operatorSchema);
    }

    public void addRecord(List<DataBox> values) throws DatabaseException {
      SortOperator.this.transaction.addRecord(this.tempTableName, values);
    }

    public void addRecords(List<Record> records) throws DatabaseException {
      for (Record r: records) {
        this.addRecord(r.getValues());
      }
    }

    public Iterator<Record> iterator() throws DatabaseException {
      return SortOperator.this.transaction.getRecordIterator(this.tempTableName);
    }

    public String tableName() {
      return this.tempTableName;
    }
  }


  /**
   * Returns a NEW run that is the sorted version of the input run.
   * Can do an in memory sort over all the records in this run
   * using one of Java's built-in sorting methods.
   * Note: Don't worry about modifying the original run.
   * Returning a new run would bring one extra page in memory beyond the
   * size of the buffer, but it is done this way for ease.
   */
  public Run sortRun(Run run) throws DatabaseException {
      Iterator<Record> runIterator = run.iterator();
      List<Record> records = new ArrayList<>();
      while (runIterator.hasNext()) {
          records.add(runIterator.next());
      }
      records.sort(comparator);
      Run sortedRun = new Run();
      sortedRun.addRecords(records);
      return sortedRun;
  }



  /**
   * Given a list of sorted runs, returns a new run that is the result
   * of merging the input runs. You should use a Priority Queue (java.util.PriorityQueue)
   * to determine which record should be should be added to the output run next.
   * It is recommended that your Priority Queue hold Pair<Record, Integer> objects
   * where a Pair (r, i) is the Record r with the smallest value you are
   * sorting on currently unmerged from run i.
   */
  public Run mergeSortedRuns(List<Run> runs) throws DatabaseException {
    int capacity = runs.size();
    PriorityQueue<Pair<Record, Integer>> queue = new PriorityQueue(capacity, new RecordPairComparator());
    Run mergedRun = new Run();
    List<Iterator> iterators = new ArrayList<>();

    for (int i = 0; i < runs.size(); i ++) {
        Run run = runs.get(i);
        Iterator<Record> runIterator = run.iterator();
        Record record = runIterator.next();
        Pair<Record, Integer> pair = new Pair(record, i);
        queue.add(pair);
        iterators.add(runIterator);

    }

    while (!queue.isEmpty()) {
        Pair<Record, Integer> pair = queue.poll();
        int index = pair.getSecond();
        mergedRun.addRecord(pair.getFirst().getValues());

        Iterator<Record> nextIterator = iterators.get(index);

        if (nextIterator.hasNext()) {
            Record record = nextIterator.next();
            Pair<Record, Integer> nextPair = new Pair(record, index);
            queue.add(nextPair);
        }

    }

    return mergedRun;
  }

  /**
   * Given a list of N sorted runs, returns a list of
   * sorted runs that is the result of merging (numBuffers - 1)
   * of the input runs at a time.
   */
  public List<Run> mergePass(List<Run> runs) throws DatabaseException {
    int n = runs.size();
    int bMinus1 = numBuffers - 1;
    List<Run> ithSortedRun;
    List<Run> newRuns = new ArrayList<>();
    for (int i = 1; i <= Math.ceil(n/bMinus1); i ++) {
        if (i*bMinus1 > n) {
            ithSortedRun = runs.subList((i-1)*bMinus1, n);
        } else {
            ithSortedRun = runs.subList((i-1)*bMinus1, i*bMinus1);
        }
        newRuns.add(mergeSortedRuns(ithSortedRun));
    }
    return newRuns;
  }


  /**
   * Does an external merge sort on the table with name tableName
   * using numBuffers.
   * Returns the name of the table that backs the final run.
   */
  public String sort() throws DatabaseException {
      RecordIterator recordIterator = this.transaction.getRecordIterator(this.tableName);
      int b = this.numBuffers * this.transaction.getNumEntriesPerPage(this.tableName);
      Record record;
      Run run = new Run();
      List<Run> runs = new ArrayList<>();
      while (recordIterator.hasNext()) {
          int i = 0;
          while (i < b) {
              if (recordIterator.hasNext()) {
                  record = recordIterator.next();
                  run.addRecord(record.getValues());
              } else {
                  break;
              }
              i +=1;
          }
          runs.add(sortRun(run));
          run = new Run();
      }

      while (runs.size() > 1) {
          runs = mergePass(runs);
      }
      return runs.get(0).tableName();
  }

  private class RecordPairComparator implements Comparator<Pair<Record, Integer>> {
    public int compare(Pair<Record, Integer> o1, Pair<Record, Integer> o2) {
      return SortOperator.this.comparator.compare(o1.getFirst(), o2.getFirst());

    }
  }
  public Run createRun() throws DatabaseException {
    return new Run();
  }
}

