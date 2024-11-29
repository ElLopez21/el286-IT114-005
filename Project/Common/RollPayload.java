package Project.Common;

// el286
// 11/27/24
public class RollPayload extends Payload{
    private String rollInput;

    public RollPayload(){
        setPayloadType(PayloadType.ROLL);
    }

    public String getRollInput(){
        return rollInput;
    }
    public void setRollInput(String rollInput){
        this.rollInput = rollInput;
    }
    public String toString(){
        return String.format("RollPayload[%s] Input[%s]", getPayloadType(), rollInput);
    }

}
