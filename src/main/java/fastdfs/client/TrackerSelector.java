package fastdfs.client;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public enum TrackerSelector {

    ROUND_ROBIN {
        private int idx;

        @Override
        public TrackerServer select(List<TrackerServer> list) {
            idx %= list.size();
            return list.get(idx++);
        }

    },
    RANDOM {
        // private final Random random = new Random(); // 优化原因：并发性能低。
        private final ThreadLocalRandom threadLocalRandom = ThreadLocalRandom.current();

        @Override
        public TrackerServer select(List<TrackerServer> list) {
            return list.get(threadLocalRandom.nextInt(list.size()));
        }

    },
    FIRST {
        @Override
        TrackerServer select(List<TrackerServer> list) {
            return list.get(0);
        }

    };

    abstract TrackerServer select(List<TrackerServer> list);
}