package qeorm.test;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import qeorm.QeApp;

import static java.lang.System.out;


@SpringBootTest(classes = QeApp.class)
public class DatasourceTest {
    @Test
    public void run() {
        out.println("========");
    }
}
