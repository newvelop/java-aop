package camp.nextstep.aop;

public class SecureBeanTarget implements SecureBean {

    public void writeSecureMessage() {
        System.out.println("Secret Message");
    }
}
