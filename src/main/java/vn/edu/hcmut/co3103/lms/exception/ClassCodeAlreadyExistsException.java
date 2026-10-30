package vn.edu.hcmut.co3103.lms.exception;


public class ClassCodeAlreadyExistsException extends RuntimeException {

    public ClassCodeAlreadyExistsException() {
        super("Class code is already in use");
    }
}
