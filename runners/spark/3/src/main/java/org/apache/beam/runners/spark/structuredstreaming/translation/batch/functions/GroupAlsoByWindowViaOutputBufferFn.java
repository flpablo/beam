/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.runners.spark.structuredstreaming.translation.batch.functions;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Supplier;
import org.apache.beam.runners.core.InMemoryTimerInternals;
import org.apache.beam.runners.core.ReduceFnRunner;
import org.apache.beam.runners.core.StateInternals;
import org.apache.beam.runners.core.StateInternalsFactory;
import org.apache.beam.runners.core.SystemReduceFn;
import org.apache.beam.runners.core.TimerInternals;
import org.apache.beam.runners.core.UnsupportedSideInputReader;
import org.apache.beam.runners.core.triggers.ExecutableTriggerStateMachine;
import org.apache.beam.runners.core.triggers.TriggerStateMachines;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.transforms.windowing.BoundedWindow;
import org.apache.beam.sdk.util.WindowedValueReceiver;
import org.apache.beam.sdk.util.construction.TriggerTranslation;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.WindowedValue;
import org.apache.beam.sdk.values.WindowingStrategy;
import org.apache.spark.api.java.function.FlatMapGroupsFunction;
import org.joda.time.Instant;

/** A FlatMap function that groups by windows in batch mode using {@link ReduceFnRunner}. */
public class GroupAlsoByWindowViaOutputBufferFn<K, InputT, W extends BoundedWindow>
    implements FlatMapGroupsFunction<
        K, WindowedValue<KV<K, InputT>>, WindowedValue<KV<K, Iterable<InputT>>>> {

  private final WindowingStrategy<?, W> windowingStrategy;
  private final StateInternalsFactory<K> stateInternalsFactory;
  private final SystemReduceFn<K, InputT, Iterable<InputT>, Iterable<InputT>, W> reduceFn;
  private final Supplier<PipelineOptions> options;

  public GroupAlsoByWindowViaOutputBufferFn(
      WindowingStrategy<?, W> windowingStrategy,
      StateInternalsFactory<K> stateInternalsFactory,
      SystemReduceFn<K, InputT, Iterable<InputT>, Iterable<InputT>, W> reduceFn,
      Supplier<PipelineOptions> options) {
    this.windowingStrategy = windowingStrategy;
    this.stateInternalsFactory = stateInternalsFactory;
    this.reduceFn = reduceFn;
    this.options = options;
  }

  @Override
  public Iterator<WindowedValue<KV<K, Iterable<InputT>>>> call(
      K key, Iterator<WindowedValue<KV<K, InputT>>> iterator) throws Exception {

    // we have to materialize the Iterator because ReduceFnRunner.processElements expects
    // to have all elements to merge the windows between each other.
    // possible OOM even though the spark framework spills to disk if a given group is too large to
    // fit in memory.
    ArrayList<WindowedValue<InputT>> values = new ArrayList<>();
    while (iterator.hasNext()) {
      WindowedValue<KV<K, InputT>> wv = iterator.next();
      values.add(wv.withValue(wv.getValue().getValue()));
    }

    // ------ based on GroupAlsoByWindowsViaOutputBufferDoFn ------//

    // Used with Batch, we know that all the data is available for this key. We can't use the
    // timer manager from the context because it doesn't exist. So we create one and emulate the
    // watermark, knowing that we have all data and it is in timestamp order.
    InMemoryTimerInternals timerInternals = new InMemoryTimerInternals();
    timerInternals.advanceProcessingTime(Instant.now());
    timerInternals.advanceSynchronizedProcessingTime(Instant.now());
    StateInternals stateInternals = stateInternalsFactory.stateInternalsForKey(key);
    GABWWindowedValueReceiver<K, InputT> outputter = new GABWWindowedValueReceiver<>();

    ReduceFnRunner<K, InputT, Iterable<InputT>, W> reduceFnRunner =
        new ReduceFnRunner<>(
            key,
            windowingStrategy,
            ExecutableTriggerStateMachine.create(
                TriggerStateMachines.stateMachineForTrigger(
                    TriggerTranslation.toProto(windowingStrategy.getTrigger()))),
            stateInternals,
            timerInternals,
            outputter,
            new UnsupportedSideInputReader("GroupAlsoByWindow"),
            reduceFn,
            options.get());

    // Process the grouped values.
    reduceFnRunner.processElements(values);

    // Finish any pending windows by advancing the input watermark to infinity.
    timerInternals.advanceInputWatermark(BoundedWindow.TIMESTAMP_MAX_VALUE);

    // Finally, advance the processing time to infinity to fire any timers.
    timerInternals.advanceProcessingTime(BoundedWindow.TIMESTAMP_MAX_VALUE);
    timerInternals.advanceSynchronizedProcessingTime(BoundedWindow.TIMESTAMP_MAX_VALUE);

    fireEligibleTimers(timerInternals, reduceFnRunner);

    reduceFnRunner.persist();

    return outputter.getOutputs().iterator();
  }

  private void fireEligibleTimers(
      InMemoryTimerInternals timerInternals,
      ReduceFnRunner<K, InputT, Iterable<InputT>, W> reduceFnRunner)
      throws Exception {
    List<TimerInternals.TimerData> timers = new ArrayList<>();
    while (true) {
      TimerInternals.TimerData timer;
      while ((timer = timerInternals.removeNextEventTimer()) != null) {
        timers.add(timer);
      }
      while ((timer = timerInternals.removeNextProcessingTimer()) != null) {
        timers.add(timer);
      }
      while ((timer = timerInternals.removeNextSynchronizedProcessingTimer()) != null) {
        timers.add(timer);
      }
      if (timers.isEmpty()) {
        break;
      }
      reduceFnRunner.onTimers(timers);
      timers.clear();
    }
  }

  private static class GABWWindowedValueReceiver<K, V>
      implements WindowedValueReceiver<KV<K, Iterable<V>>> {
    private final List<WindowedValue<KV<K, Iterable<V>>>> outputs = new ArrayList<>();

    @Override
    public void output(WindowedValue<KV<K, Iterable<V>>> value) {
      outputs.add(value);
    }

    Iterable<WindowedValue<KV<K, Iterable<V>>>> getOutputs() {
      return outputs;
    }
  }
}
